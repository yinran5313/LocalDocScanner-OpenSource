# 实际Compose界面预览

运行`./gradlew -PuiPreview=true :ui-preview:assembleDebug`。使用主App的HomeScreen、ToolGrid、DocList、Theme、ScannerComponents真实源码和res；独立applicationId，不包含扫描、OCR或Office引擎，也不与用户主App数据交互。仅用于界面可视检查，不能当业务流程验收。

PreviewActivity支持Intent参数screen=home/tasks/result，dark=true，empty=true，draft=true。结果页为共有组件示例；首页和菜单使用真实HomeScreen。
