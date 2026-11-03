/**
 * 定位客户端 WZ 资源目录（Data）。
 * 目录位置会随部署方式变化，这里从当前文件向上逐级查找，避免写死相对层级。
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

function locate() {
  let dir = __dirname;
  for (let i = 0; i < 6; i++) {
    const candidate = path.join(dir, 'BeiDou-ClientV17', 'BeiDou-Client', 'Data');
    if (fs.existsSync(candidate)) return candidate;
    dir = path.dirname(dir);
  }
  return path.resolve(__dirname, '../../../BeiDou-ClientV17/BeiDou-Client/Data');
}

export const DATA_DIR = locate();
