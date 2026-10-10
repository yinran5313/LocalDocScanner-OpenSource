const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/localdoc-office-ui.js'), 'utf8');
function fixture(type = 'text', zoom = 6, android = true) {
  const calls = [];
  function Map() { this.zoom = zoom; this._docLayer = {_docType: type}; }
  Map.prototype.getZoom = function () { return this.zoom; };
  Map.prototype.setZoom = function (value) { calls.push(value); this.zoom = value; return this; };
  Map.prototype._enterEditMode = function (permission) {
    this._permission = permission;
    if (type === 'text') this.setZoom(10);
    if (this.additionalZoom) this.setZoom(10, {animate: false});
    if (this.fail) throw new Error('entry failure');
    return 'entered';
  };
  const window = {ThisIsTheAndroidApp: android, L: {Map}};
  const context = vm.createContext({window});
  vm.runInContext(source, context);
  vm.runInContext(source, context); // Idempotent even if entry HTML is reinjected.
  return {map: new Map(), calls, originalZoom: Map.prototype.setZoom};
}
for (const zoom of [4, 6, 10, 13]) {
  const f = fixture('text', zoom);
  assert.equal(f.map._enterEditMode('edit'), 'entered');
  assert.equal(f.map._permission, 'edit');
  assert.equal(f.map.zoom, zoom);
  assert.equal(f.calls.length, 0);
  assert.equal(f.map.setZoom, f.originalZoom);
  assert.equal(Object.hasOwn(f.map, 'setZoom'), false);
  f.map.setZoom(10); // Subsequent explicit user zoom is allowed.
  assert.equal(f.map.zoom, 10);
}
for (const type of ['spreadsheet', 'presentation']) {
  const f = fixture(type); f.map._enterEditMode('edit'); assert.equal(f.map.zoom, 6);
}
const additional = fixture(); additional.map.additionalZoom = true;
additional.map._enterEditMode('edit'); assert.deepEqual(additional.calls, [10]);
const failing = fixture(); failing.map.fail = true;
assert.throws(() => failing.map._enterEditMode('edit'), /entry failure/);
assert.equal(failing.map.setZoom, failing.originalZoom);
const own = fixture(); own.map.setZoom = own.originalZoom;
own.map._enterEditMode('edit'); assert.equal(Object.hasOwn(own.map, 'setZoom'), true);
const desktop = fixture('text', 6, false); desktop.map._enterEditMode('edit'); assert.equal(desktop.map.zoom, 10);
const uninitialized = fixture('text', undefined); uninitialized.map.zoom = undefined;
uninitialized.map._enterEditMode('edit'); assert.equal(uninitialized.map.zoom, 10);
console.log('Office edit zoom tests passed: reading scale, permissions, manual zoom, other document types, restoration and idempotence.');
