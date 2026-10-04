/* Local overlay for Collabora's existing JSDialog.MessageRouter. Does not modify pinned runtime files. */
(function (root) {
  'use strict';
  const lang = (root.LANG || '').replace(/_/g, '-');
  if (!/^zh(?:-|$)/i.test(lang) || !root.JSDialog || !root.JSDialog.MessageRouter) return;
  const traditional = /(?:Hant|TW|HK|MO)/i.test(lang);
  const words = root.LocalDocOfficeChinese && root.LocalDocOfficeChinese[traditional ? 'zh-TW' : 'zh-CN'] || {};
  const source = 'One or more fonts embedded in the document have no editing permission.\nOpen document in read-only mode?\nPressing [ No ] will drop these fonts from the document:\n';
  const fontMessage = traditional
    ? '此文件包含不允許編輯的嵌入字型。\n可選擇唯讀開啟，保留目前排版；或移除以下嵌入字型後編輯，排版可能改變：\n'
    : '此文档包含不允许编辑的嵌入字体。\n可选择只读打开，保留当前排版；或移除以下嵌入字体后编辑，排版可能改变：\n';
  const translate = function (text) {
    if (typeof text !== 'string') return text;
    const normalized = text.replace(/\r\n/g, '\n');
    if (normalized.startsWith(source)) return fontMessage + normalized.slice(source.length);
    if (normalized === source.split('\n')[0]) return traditional ? '此文件包含不允許編輯的嵌入字型。' : '此文档包含不允许编辑的嵌入字体。';
    if (normalized === source.split('\n')[1]) return traditional ? '是否唯讀開啟並保留目前排版？' : '是否只读打开并保留当前排版？';
    if (normalized === source.split('\n')[2]) return traditional ? '移除以下嵌入字型後可編輯，排版可能改變：' : '移除以下嵌入字体后可编辑，排版可能改变：';
    return words[text] || words[normalized] || text;
  };
  const hasFontWarning = function (node) {
    return node && (typeof node.text === 'string' &&
      (/Font Disallows Editing|字体禁止编辑|字型.*編輯/.test(node.text) || node.text.startsWith(source.split('\n')[0])) ||
      Array.isArray(node.children) && node.children.some(hasFontWarning));
  };
  const translateMessage = function (node, fontWarning, isRoot) {
    if (!node || typeof node !== 'object') return;
    // Translate UI labels only. Editable input values and document content are untouched.
    if (typeof node.text === 'string' && (isRoot || ['fixedtext', 'button', 'pushbutton', 'responsebutton'].includes(node.type))) {
      const originalText = node.text.replace(/[~&_]/g, '');
      node.text = translate(node.text);
      if (fontWarning && isRoot) node.text = traditional ? '拾頁 · 字型編輯受限' : '拾页 · 字体编辑受限';
      if (fontWarning && ['button', 'pushbutton', 'responsebutton'].includes(node.type)) {
        const yes = /^Yes$/.test(originalText) || /^(?:是|確定|确定)$/.test(originalText);
        const no = /^No$/.test(originalText) || /^否$/.test(originalText);
        if (yes) node.text = traditional ? '唯讀開啟' : '只读打开';
        if (no) node.text = traditional ? '移除嵌入字型並編輯' : '移除嵌入字体并编辑';
      }
    }
    if (Array.isArray(node.children)) node.children.forEach(child => translateMessage(child, fontWarning, false));
  };
  const router = root.JSDialog.MessageRouter;
  const original = router.processMessage;
  router.processMessage = function (message, callback) {
    if (message && message.type === 'messagebox') translateMessage(message, hasFontWarning(message), true);
    return original.call(this, message, callback);
  };
})(window);
