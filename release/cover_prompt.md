# 侧漏 Side Drain — MC百科条目封面 方案

> 本机三路出图全部不可用（本地 SD 未装且安装脚本联网拉取卡死、Vision Mix 未配密钥、云端 generate_image 返回 401）。
> 下方为可直接用本地 SD（AWPainting / majicmix）一键出图的提示词与版式，环境就绪后直接跑。

## 版式
- 建议尺寸：**1536×1024（3:2 横幅）**，顶部留白放标题「侧漏 / Side Drain」。
- 也可出 1:1 方形再裁。Minecraft 方块像素风 / 等距视角。

## 主视觉（三件招牌物，来自 MCMOD.md 文案）
1. **朝向扳手**：青色发光，45° 倾斜，正在旋转一块圆石。
2. **侧漏板**：铜色，贴在木箱侧面，喷口向外喷射物品。
3. **方块手套**：皮革质感，抱着一个「还活着的」草方块。

## 配色与母题
- 青（teal）/ 铜（copper）主色；水滴 / 泄漏（leak / drain）母题点缀。
- 无人物、无文字（标题后加）；干净几何构图，Minecraft 游戏 UI 质感。

## SD 正向提示词（英文标签式，照 sd-paint 规范）
minecraft mod cover banner, flat blocky minecraft game art style, isometric view, teal and copper color palette, central composition featuring three signature items: a glowing cyan-teal orientation wrench tilted 45 degrees rotating a cobblestone block, a copper side-drain plate attached to the side face of a wooden chest with a spout spraying items outward, a leather block-glove carrying a living grass block, subtle water droplet leak motif, clean geometric layout with empty top area for title, vibrant saturated colors, game UI aesthetic, no characters, masterpiece, best quality, absurdres, highly detailed, sharp pixel-clean edges

## SD 负向提示词
lowres, bad anatomy, bad hands, extra fingers, fewer digits, watermark, text, signature, jpeg artifacts, worst quality, low quality, extra limbs, distorted

## 参数
- 模型：AWPainting v1.2（动漫）或 majicmix（写实二次元）
- 采样器：DPM++ 2M；步数 28；CFG 7
- 尺寸：1536×1024
- 出图后自检：手部/肢体、水印、构图、Minecraft 风格是否到位（按 sd-paint「出图自检→优化循环」最多 2 轮）

## 备选（更省算力，单件物聚焦）
把三件物拆成并排三格，每格突出一件，标题跨顶部——适合 MC百科 的窄封面位。
