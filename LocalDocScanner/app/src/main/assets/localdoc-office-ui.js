/* Local integration overlay; the pinned Collabora payload remains unchanged. */
(function (root) {
  'use strict';
  const prototype = root.L && root.L.Map && root.L.Map.prototype;
  if (!root.ThisIsTheAndroidApp || !prototype || !prototype._enterEditMode || prototype._localdocKeepsEditZoom) return;
  const enterEdit = prototype._enterEditMode;
  prototype._enterEditMode = function () {
    const zoom = this.getZoom && this.getZoom();
    // The mobile Writer entry resets its view to zoom 10. Keep the user's
    // reading scale; keyboard focus, permissions and all other editing steps stay intact.
    if (!this._docLayer || this._docLayer._docType !== 'text' || !Number.isFinite(zoom)) {
      return enterEdit.apply(this, arguments);
    }
    const hadOwnZoom = Object.prototype.hasOwnProperty.call(this, 'setZoom');
    const setZoom = this.setZoom;
    this.setZoom = function (nextZoom) {
      if (nextZoom === 10 && arguments.length === 1) return this;
      return setZoom.apply(this, arguments);
    };
    try {
      return enterEdit.apply(this, arguments);
    } finally {
      if (hadOwnZoom) this.setZoom = setZoom;
      else delete this.setZoom;
    }
  };
  prototype._localdocKeepsEditZoom = true;
})(window);
