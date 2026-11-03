package org.gms.service;

import com.mybatisflex.core.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.gms.client.Character;
import org.gms.client.inventory.Inventory;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.Item;
import org.gms.client.inventory.ItemFactory;
import org.gms.client.inventory.manipulator.InventoryManipulator;
import org.gms.client.inventory.manipulator.KarmaManipulator;
import org.gms.dao.entity.CharactersDO;
import org.gms.dao.mapper.CharactersMapper;
import org.gms.exception.BizException;
import org.gms.model.dto.H5ItemReqDTO;
import org.gms.net.server.Server;
import org.gms.net.server.world.World;
import org.gms.server.ItemInformationProvider;
import org.gms.util.DatabaseConnection;
import org.gms.util.Pair;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * H5 背包物品操作：丢弃与赠送。
 * <p>
 * 在线角色走游戏内存中的背包并同步下发背包变更包；离线角色直接操作 {@code inventoryitems}
 * 表。赠送复用端游快递（Duey）机制，物品存入 {@code dueypackages}/{@code dueyitems} 对应的
 * 快递存储，收件人在游戏内打开快递即可领取。
 *
 * @since 2026-11-03
 */
@Service
@Slf4j
public class H5ItemService {
    /** 背包物品写入 inventoryitems 的 type */
    private static final int STORAGE_INVENTORY = 1;
    private static final int MAX_MESSAGE_LENGTH = 100;

    private final CharactersMapper charactersMapper;

    public H5ItemService(CharactersMapper charactersMapper) {
        this.charactersMapper = charactersMapper;
    }

    /** 丢弃背包物品：直接从背包移除，不可找回 */
    public void dropItem(H5ItemReqDTO req) {
        CharactersDO chr = requireCharacter(req.getCharacterId());
        InventoryType type = requireInventoryType(req.getInventoryType());
        int position = requirePosition(req.getPosition());
        long requested = Optional.ofNullable(req.getQuantity()).orElse(0L);

        Character online = findOnlineCharacter(chr.getWorld(), chr.getId());
        if (online != null) {
            dropOnline(online, type, position, requested);
        } else {
            dropOffline(chr.getId(), type, position, requested);
        }
    }

    /** 赠送背包物品给其他角色，通过快递包裹送达 */
    public void giftItem(H5ItemReqDTO req) {
        CharactersDO sender = requireCharacter(req.getCharacterId());
        InventoryType type = requireInventoryType(req.getInventoryType());
        int position = requirePosition(req.getPosition());
        long requested = Optional.ofNullable(req.getQuantity()).orElse(0L);

        String receiverName = req.getReceiverName() == null ? "" : req.getReceiverName().trim();
        if (receiverName.isEmpty()) {
            throw new BizException("请输入收件人角色名");
        }
        String message = req.getMessage() == null ? "" : req.getMessage().trim();
        if (message.length() > MAX_MESSAGE_LENGTH) {
            message = message.substring(0, MAX_MESSAGE_LENGTH);
        }

        CharactersDO receiver = charactersMapper.selectOneByQuery(QueryWrapper.create().eq("name", receiverName));
        if (receiver == null) {
            throw new BizException("收件人角色不存在");
        }
        if (receiver.getId().equals(sender.getId())) {
            throw new BizException("不能赠送给自己");
        }
        if (receiver.getAccountid() != null && receiver.getAccountid().equals(sender.getAccountid())) {
            throw new BizException("不能赠送给同一账号下的角色");
        }

        Character online = findOnlineCharacter(sender.getWorld(), sender.getId());
        if (online != null) {
            giftOnline(online, sender, receiver, type, position, requested, message);
        } else {
            giftOffline(sender, receiver, type, position, requested, message);
        }
    }

    private void dropOnline(Character chr, InventoryType type, int position, long requested) {
        Inventory inv = chr.getInventory(type);
        long quantity;
        inv.lockInventory();
        try {
            Item item = inv.getItem((short) position);
            if (item == null) {
                throw new BizException("物品不存在或位置已变化，请刷新后重试");
            }
            quantity = resolveQuantity(type, item, requested);
        } finally {
            inv.unlockInventory();
        }
        InventoryManipulator.removeFromSlot(chr.getClient(), type, (short) position, (short) quantity, false);
    }

    private void dropOffline(int characterId, InventoryType type, int position, long requested) {
        try (Connection con = DatabaseConnection.getConnection()) {
            con.setAutoCommit(false);
            try {
                long[] found = selectItemRow(con, characterId, type, position);
                if (found == null) {
                    throw new BizException("物品不存在或位置已变化，请刷新后重试");
                }
                long quantity = resolveQuantity(type, found[1], requested);
                if (quantity >= found[1]) {
                    deleteItemRow(con, characterId, type, position);
                } else {
                    reduceItemQuantity(con, characterId, type, position, quantity);
                }
                con.commit();
            } catch (BizException e) {
                con.rollback();
                throw e;
            } catch (SQLException e) {
                con.rollback();
                throw new BizException("丢弃失败：" + e.getMessage());
            } finally {
                con.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new BizException("丢弃失败：" + e.getMessage());
        }
    }

    private void giftOnline(Character chr, CharactersDO sender, CharactersDO receiver,
                            InventoryType type, int position, long requested, String message) {
        Item copy;
        long quantity;
        Inventory inv = chr.getInventory(type);
        inv.lockInventory();
        try {
            Item item = inv.getItem((short) position);
            if (item == null) {
                throw new BizException("物品不存在或位置已变化，请刷新后重试");
            }
            quantity = resolveQuantity(type, item, requested);
            checkTradeable(item);
            copy = item.copy();
            copy.setQuantity((short) quantity);
        } finally {
            inv.unlockInventory();
        }

        int packageId = createPackage(sender, receiver, copy, type, message);
        try {
            InventoryManipulator.removeFromSlot(chr.getClient(), type, (short) position, (short) quantity, false);
        } catch (RuntimeException e) {
            removePackage(packageId);
            throw new BizException("赠送失败：" + e.getMessage());
        }
    }

    private void giftOffline(CharactersDO sender, CharactersDO receiver,
                             InventoryType type, int position, long requested, String message) {
        Item item = loadOfflineItem(sender.getId(), type, position);
        if (item == null) {
            throw new BizException("物品不存在或位置已变化，请刷新后重试");
        }
        long quantity = resolveQuantity(type, item, requested);
        checkTradeable(item);

        Item copy = item.copy();
        copy.setQuantity((short) quantity);
        int packageId = createPackage(sender, receiver, copy, type, message);
        try {
            removeSenderItem(sender.getId(), type, position, quantity);
        } catch (BizException e) {
            removePackage(packageId);
            throw e;
        }
    }

    /** 目标物品必须可交易，与端游快递的校验保持一致 */
    private void checkTradeable(Item item) {
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        if (item.isUntradeable() || ii.isUnmerchable(item.getItemId())) {
            throw new BizException("该物品不可交易，无法赠送");
        }
    }

    private long resolveQuantity(InventoryType type, Item item, long requested) {
        long available = item.getQuantity();
        if (type == InventoryType.EQUIP || type == InventoryType.CASH) {
            return 1;
        }
        if (requested <= 0 || requested > available) {
            return available;
        }
        return requested;
    }

    private long resolveQuantity(InventoryType type, long available, long requested) {
        if (type == InventoryType.EQUIP || type == InventoryType.CASH) {
            return 1;
        }
        if (requested <= 0 || requested > available) {
            return available;
        }
        return requested;
    }

    /** 创建快递包裹并把物品写入快递存储 */
    private int createPackage(CharactersDO sender, CharactersDO receiver, Item item, InventoryType type, String message) {
        // 与端游快递一致：带 Karma 标记的物品寄出后变为不可交易
        KarmaManipulator.toggleKarmaFlagToUntradeable(item);
        try (Connection con = DatabaseConnection.getConnection()) {
            con.setAutoCommit(false);
            try {
                int packageId = insertPackageRow(con, receiver.getId(), sender.getName(), message);
                ItemFactory.DUEY.saveItems(List.of(new Pair<>(item, type)), packageId, con);
                con.commit();
                return packageId;
            } catch (SQLException e) {
                con.rollback();
                throw new BizException("创建快递包裹失败：" + e.getMessage());
            } finally {
                con.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new BizException("创建快递包裹失败：" + e.getMessage());
        }
    }

    private int insertPackageRow(Connection con, int receiverId, String senderName, String message) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "INSERT INTO `dueypackages` (ReceiverId, SenderName, Mesos, TimeStamp, Message, Type, Checked) VALUES (?, ?, 0, ?, ?, 0, 1)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, receiverId);
            ps.setString(2, senderName);
            ps.setTimestamp(3, new Timestamp(System.currentTimeMillis()));
            ps.setString(4, message);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (!rs.next()) {
                    throw new SQLException("创建快递包裹失败");
                }
                return rs.getInt(1);
            }
        }
    }

    /** 失败回滚时清理已写入的快递包裹 */
    private void removePackage(int packageId) {
        try (Connection con = DatabaseConnection.getConnection()) {
            ItemFactory.DUEY.saveItems(List.of(), packageId, con);
            try (PreparedStatement ps = con.prepareStatement("DELETE FROM `dueypackages` WHERE PackageId = ?")) {
                ps.setInt(1, packageId);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            log.warn("清理快递包裹 {} 失败：{}", packageId, e.getMessage());
        }
    }

    private void removeSenderItem(int characterId, InventoryType type, int position, long quantity) {
        try (Connection con = DatabaseConnection.getConnection()) {
            con.setAutoCommit(false);
            try {
                long[] found = selectItemRow(con, characterId, type, position);
                if (found == null) {
                    throw new BizException("物品不存在或位置已变化，请刷新后重试");
                }
                if (quantity >= found[1]) {
                    deleteItemRow(con, characterId, type, position);
                } else {
                    reduceItemQuantity(con, characterId, type, position, quantity);
                }
                con.commit();
            } catch (BizException e) {
                con.rollback();
                throw e;
            } catch (SQLException e) {
                con.rollback();
                throw new BizException("赠送失败：" + e.getMessage());
            } finally {
                con.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new BizException("赠送失败：" + e.getMessage());
        }
    }

    private Item loadOfflineItem(int characterId, InventoryType type, int position) {
        try {
            for (Pair<Item, InventoryType> pair : ItemFactory.INVENTORY.loadItems(characterId, false)) {
                Item item = pair.getLeft();
                if (item != null && pair.getRight() == type && item.getPosition() == position) {
                    return item;
                }
            }
        } catch (SQLException e) {
            throw new BizException("读取背包失败：" + e.getMessage());
        }
        return null;
    }

    /** 返回 [itemId, quantity]，不存在时返回 null */
    private long[] selectItemRow(Connection con, int characterId, InventoryType type, int position) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT itemid, quantity FROM `inventoryitems` WHERE `type` = ? AND `characterid` = ? AND `inventorytype` = ? AND `position` = ?")) {
            ps.setInt(1, STORAGE_INVENTORY);
            ps.setString(2, String.valueOf(characterId));
            ps.setInt(3, type.getType());
            ps.setInt(4, position);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new long[]{rs.getInt("itemid"), rs.getInt("quantity")};
            }
        }
    }

    private void deleteItemRow(Connection con, int characterId, InventoryType type, int position) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "DELETE `inventoryitems`, `inventoryequipment` FROM `inventoryitems` LEFT JOIN `inventoryequipment`"
                        + " USING(`inventoryitemid`) WHERE `inventoryitems`.`type` = ? AND `inventoryitems`.`characterid` = ?"
                        + " AND `inventoryitems`.`inventorytype` = ? AND `inventoryitems`.`position` = ?")) {
            ps.setInt(1, STORAGE_INVENTORY);
            ps.setString(2, String.valueOf(characterId));
            ps.setInt(3, type.getType());
            ps.setInt(4, position);
            ps.executeUpdate();
        }
    }

    private void reduceItemQuantity(Connection con, int characterId, InventoryType type, int position, long quantity) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE `inventoryitems` SET `quantity` = `quantity` - ? WHERE `type` = ? AND `characterid` = ?"
                        + " AND `inventorytype` = ? AND `position` = ?")) {
            ps.setLong(1, quantity);
            ps.setInt(2, STORAGE_INVENTORY);
            ps.setString(3, String.valueOf(characterId));
            ps.setInt(4, type.getType());
            ps.setInt(5, position);
            ps.executeUpdate();
        }
    }

    private CharactersDO requireCharacter(Integer characterId) {
        if (characterId == null || characterId <= 0) {
            throw new BizException("角色参数错误");
        }
        CharactersDO chr = charactersMapper.selectOneById(characterId);
        if (chr == null) {
            throw new BizException("角色不存在");
        }
        return chr;
    }

    private InventoryType requireInventoryType(Integer inventoryType) {
        int value = inventoryType == null ? 0 : inventoryType;
        InventoryType type = value > 0 && value <= 4 ? InventoryType.getByType((byte) value) : null;
        if (type == null) {
            throw new BizException("不支持该背包栏位的操作");
        }
        return type;
    }

    private int requirePosition(Integer position) {
        int value = position == null ? 0 : position;
        if (value <= 0) {
            throw new BizException("物品位置无效，请刷新后重试");
        }
        return value;
    }

    private Character findOnlineCharacter(int world, int characterId) {
        World w = Server.getInstance().getWorld(world);
        return w == null ? null : w.getPlayerStorage().getCharacterById(characterId);
    }
}
