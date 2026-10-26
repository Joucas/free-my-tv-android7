/* Static Canvas backgrounds, including software blur for older TV browsers. */
var Wallpapers = (function () {
  'use strict';
  var choices = ['ocean', 'dunes', 'forest', 'sunset', 'neutral', 'auto'];
  var last = '', glass = '';
  function name(id) {
    switch (id) {
      case 'ocean': return tr('Ocean');
      case 'dunes': return tr('Dunes');
      case 'forest': return tr('Forest');
      case 'sunset': return tr('Sunset');
      case 'neutral': return tr('Neutral');
      default: return tr('Automatic');
    }
  }
  function box(src, dst, w, h, radius, horizontal) {
    var n = horizontal ? w : h, lines = horizontal ? h : w;
    var step = horizontal ? 4 : w * 4, lineStep = horizontal ? w * 4 : 4;
    var row, channel, i, sum, base, at, add, sub, scale = 1 / (2 * radius + 1);
    for (row = 0; row < lines; row++) {
      base = row * lineStep;
      for (channel = 0; channel < 3; channel++) {
        sum = 0;
        for (i = -radius; i <= radius; i++) sum += src[base + Math.max(0, Math.min(n - 1, i)) * step + channel];
        for (i = 0; i < n; i++) {
          at = base + i * step;
          dst[at + channel] = Math.round(sum * scale);
          dst[at + 3] = 255;
          add = base + Math.min(n - 1, i + radius + 1) * step + channel;
          sub = base + Math.max(0, i - radius) * step + channel;
          sum += src[add] - src[sub];
        }
      }
    }
  }
  function blurred(data, radius) {
    var out = new Uint8ClampedArray(data), temp = new Uint8ClampedArray(out.length), pass;
    if (radius) for (pass = 0; pass < 3; pass++) {
      box(out, temp, 640, 360, radius, true);
      box(temp, out, 640, 360, radius, false);
    }
    return out;
  }
  function tint(pixels, brightness, night) {
    var amount = night ? 0.86 - brightness / 100 * 0.44 : brightness / 100 * 0.55;
    var base = night ? 12 : 255, i;
    for (i = 0; i < pixels.length; i += 4) {
      pixels[i] = pixels[i] * (1 - amount) + base * amount;
      pixels[i + 1] = pixels[i + 1] * (1 - amount) + base * amount;
      pixels[i + 2] = pixels[i + 2] * (1 - amount) + base * amount;
    }
    return pixels;
  }
  function render(night) {
    var id = Prefs.background(), canvas = document.getElementById('wallpaper');
    canvas.style.display = id === 'auto' ? 'none' : '';
    if (id === 'auto') { glass = ''; last = ''; return; }
    var strength = Prefs.backgroundBlur() ? Prefs.backgroundStrength() : 0;
    var key = id + ':' + strength + ':' + Prefs.backgroundBrightness() + ':' + night;
    if (key === last) return;
    last = key;
    var palettes = {
      ocean: ['#CEE7EF', '#91C8DC', '#629FB9', '#477B9B'],
      dunes: ['#E8D7BD', '#C8A788', '#BD9376', '#93786C'],
      forest: ['#BCD2C7', '#759D8F', '#527C70', '#365D58'],
      sunset: ['#DACDE8', '#F2B9A9', '#C08AAB', '#796D9A'],
      neutral: ['#E7E8EE', '#C7CBD5', '#BEC4D0', '#A5ADBD']
    };
    var colors = palettes[id] || palettes.ocean, ctx = canvas.getContext('2d');
    var gradient = ctx.createLinearGradient(0, 0, 640, 360);
    gradient.addColorStop(0, colors[0]); gradient.addColorStop(1, colors[1]);
    ctx.fillStyle = gradient; ctx.fillRect(0, 0, 640, 360);
    ctx.beginPath(); ctx.moveTo(-20, 240); ctx.bezierCurveTo(140, 80, 300, 330, 660, 120);
    ctx.lineTo(660, 380); ctx.lineTo(-20, 380); ctx.closePath(); ctx.fillStyle = colors[2]; ctx.fill();
    ctx.beginPath(); ctx.moveTo(-20, 300); ctx.bezierCurveTo(210, 170, 400, 380, 660, 240);
    ctx.lineTo(660, 380); ctx.lineTo(-20, 380); ctx.closePath(); ctx.fillStyle = colors[3]; ctx.fill();
    var raw = ctx.getImageData(0, 0, 640, 360), image = ctx.createImageData(640, 360);
    image.data.set(tint(blurred(raw.data, 16), night ? 50 : 80, night));
    ctx.putImageData(image, 0, 0); glass = canvas.toDataURL('image/jpeg', 0.85);
    image.data.set(tint(blurred(raw.data, Math.round(strength * 0.24)), Prefs.backgroundBrightness(), night));
    ctx.putImageData(image, 0, 0);
  }
  function surfaces(night) {
    var nodes = document.querySelectorAll('.hub-panel, #platter, #hub-status, #drawer .key');
    var i, node, x, y, tone = night ? 'rgba(17,17,19,0.6)' : 'rgba(255,255,255,0.76)';
    for (i = 0; i < nodes.length; i++) {
      node = nodes[i];
      if (!glass) { node.style.backgroundImage = ''; continue; }
      x = node.offsetLeft; y = node.offsetTop;
      if (node.parentNode.id === 'dashboard') y += 194;
      node.style.backgroundImage = 'linear-gradient(' + tone + ',' + tone + '),url(' + glass + ')';
      node.style.backgroundSize = '1920px 1080px';
      node.style.backgroundPosition = '-' + x + 'px -' + y + 'px';
    }
  }
  return {choices: choices, name: name, render: render, surfaces: surfaces};
})();
