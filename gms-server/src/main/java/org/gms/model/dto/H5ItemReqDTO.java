package org.gms.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * H5 背包物品操作请求：丢弃或赠送给其他角色。
 *
 * @since 2026-11-03
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class H5ItemReqDTO {
    private Integer characterId;
    /** 背包类型：1 装备 2 消耗 3 设置 4 其它（不支持已穿戴与现金栏） */
    private Integer inventoryType;
    /** 背包格子位置 */
    private Integer position;
    /** 操作数量，装备固定为 1 */
    private Long quantity;
    /** 赠送收件人角色名 */
    private String receiverName;
    /** 赠送留言，最长 100 字 */
    private String message;
}
