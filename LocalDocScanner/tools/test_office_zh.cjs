const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const path = require('node:path');
const assets = path.join(__dirname, '../app/src/main/assets');
const body = 'One or more fonts embedded in the document have no editing permission.\nOpen document in read-only mode?\nPressing [ No ] will drop these fonts from the document:\n仿宋 微软雅黑 方正小标宋简体 楷体';
function run(lang, message) {
  let delivered, seenCallback;
  const callback = () => {};
  const window = { LANG: lang, JSDialog: { MessageRouter: { processMessage(msg, cb) { delivered = msg; seenCallback = cb; return 42; } } } };
  const context = vm.createContext({window});
  vm.runInContext(fs.readFileSync(path.join(assets, 'localdoc-office-zh-data.js'), 'utf8'), context);
  vm.runInContext(fs.readFileSync(path.join(assets, 'localdoc-office-zh.js'), 'utf8'), context);
  assert.equal(window.JSDialog.MessageRouter.processMessage(message, callback), 42);
  assert.equal(seenCallback, callback);
  return delivered;
}
function warning() {
  return {type:'messagebox', jsontype:'dialog', text:'Collabora Office - Font Disallows Editing', children:[
    {type:'fixedtext', text:body}, {type:'responsebutton', id:'no', response:7, text:'~No'},
    {type:'responsebutton', id:'yes', response:6, text:'~Yes'}]};
}
const chinese = run('zh-CN', warning());
assert.equal(chinese.text, '拾页 · 字体编辑受限');
assert.match(chinese.children[0].text, /排版可能改变/);
assert.ok(chinese.children[0].text.endsWith('仿宋 微软雅黑 方正小标宋简体 楷体'));
assert.equal(chinese.children[1].text, '移除嵌入字体并编辑');
assert.equal(chinese.children[1].response, 7);
assert.equal(chinese.children[2].text, '只读打开');
assert.equal(chinese.children[2].response, 6);
assert.match(run('zh-TW', warning()).children[0].text, /排版可能改變/);
assert.deepEqual(run('en-US', warning()), warning());
const editable = {type:'messagebox', text:'Error', children:[{type:'edit', text:'Yes', value:'Font Disallows Editing'}, {type:'fixedtext',text:'Cancel'}]};
assert.equal(run('zh-CN', editable).children[0].text, 'Yes');
const split = warning(); split.children[0].text = body.split('\n')[0];
assert.match(run('zh-CN', split).children[0].text, /不允许编辑/);
console.log('Office Chinese dialog tests passed: dynamic fonts, simplified/traditional, button semantics, callbacks and editable data preservation.');
