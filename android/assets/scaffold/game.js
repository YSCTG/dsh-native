/*
 * 地下城 — 手机端 roguelike 骨架
 *
 * 不依赖任何 npm 包：纯 canvas 2D + 回合制。这样
 *   ① 生成即可玩，不用先装东西
 *   ② 打包成单文件时没有任何外部依赖
 * 想上 PixiJS/Phaser 时，把渲染层换掉即可，逻辑（地图/怪物/战斗）都能留。
 */
(() => {
  const W = 21, H = 21, S = 320 / W;      // 地图 21x21，格子边长
  const cv = document.getElementById('cv');
  const ctx = cv.getContext('2d');
  const $hp = document.getElementById('hp');
  const $depth = document.getElementById('depth');
  const $gold = document.getElementById('gold');
  const $log = document.getElementById('log');

  const rnd = n => Math.floor(Math.random() * n);
  const rint = (a, b) => a + rnd(b - a + 1);

  let map, seen, player, monsters, stairs, depth, over;

  // ---------- 地图 ----------
  const TILE_WALL = 0, TILE_FLOOR = 1;

  function genMap() {
    map = Array.from({length: H}, () => new Array(W).fill(TILE_WALL));
    seen = Array.from({length: H}, () => new Array(W).fill(false));
    const rooms = [];
    for (let i = 0; i < 28; i++) {
      const w = rint(3, 6), h = rint(3, 6);
      const x = rint(1, W - w - 2), y = rint(1, H - h - 2);
      const r = {x, y, w, h};
      if (rooms.some(o => x < o.x + o.w + 1 && x + w + 1 > o.x &&
                          y < o.y + o.h + 1 && y + h + 1 > o.y)) continue;
      rooms.push(r);
      for (let yy = y; yy < y + h; yy++)
        for (let xx = x; xx < x + w; xx++) map[yy][xx] = TILE_FLOOR;
      if (rooms.length > 1) {
        const p = rooms[rooms.length - 2];
        const ax = (p.x + p.w >> 1), ay = (p.y + p.h >> 1);
        const bx = (x + w >> 1), by = (y + h >> 1);
        let cx = ax, cy = ay;
        while (cx !== bx) { map[cy][cx] = TILE_FLOOR; cx += cx < bx ? 1 : -1; }
        while (cy !== by) { map[cy][cx] = TILE_FLOOR; cy += cy < by ? 1 : -1; }
      }
    }
    if (rooms.length < 2) return genMap();
    const center = r => ({x: r.x + (r.w >> 1), y: r.y + (r.h >> 1)});
    const start = center(rooms[0]);
    const end = center(rooms[rooms.length - 1]);
    player = {x: start.x, y: start.y, hp: 10, maxHp: 10, atk: 3, gold: 0};
    stairs = {x: end.x, y: end.y};
    monsters = [];
    for (let i = 1; i < rooms.length; i++) {
      if (Math.random() < 0.45) continue;
      const c = center(rooms[i]);
      monsters.push({x: c.x, y: c.y, hp: 3 + depth, atk: 1 + (depth > 2 ? 1 : 0)});
    }
  }

  function passable(x, y) {
    return x >= 0 && y >= 0 && x < W && y < H && map[y][x] === TILE_FLOOR;
  }
  function monsterAt(x, y) {
    return monsters.find(m => m.x === x && m.y === y);
  }

  // ---------- 动作 ----------
  function log(msg) { $log.textContent = msg; }

  function move(dx, dy) {
    if (over) return;
    const nx = player.x + dx, ny = player.y + dy;
    const m = monsterAt(nx, ny);
    if (m) {
      const dmg = Math.max(1, player.atk + rint(-1, 1));
      m.hp -= dmg;
      if (m.hp <= 0) {
        monsters = monsters.filter(o => o !== m);
        player.gold += rint(1, 3);
        log('击杀一只怪物，+' + player.gold + ' 金币');
      } else {
        log('命中，造成 ' + dmg + ' 伤害');
      }
    } else if (passable(nx, ny)) {
      player.x = nx; player.y = ny;
      if (nx === stairs.x && ny === stairs.y) {
        depth++;
        genMap();
        syncHud();
        draw();
        log('走下台阶，第 ' + depth + ' 层');
        return;
      }
    } else {
      log('前面是墙');
    }
    monstersTurn();
    syncHud();
    draw();
    if (over) log('你倒下了…… 刷新页面重来');
  }

  function monstersTurn() {
    for (const m of monsters) {
      const dx = Math.sign(player.x - m.x), dy = Math.sign(player.y - m.y);
      const dist = Math.abs(player.x - m.x) + Math.abs(player.y - m.y);
      if (dist > 8) continue;
      if (dist === 1) {
        player.hp -= m.atk;
        if (player.hp <= 0) { player.hp = 0; over = true; return; }
        continue;
      }
      const tryStep = (x, y) => (passable(x, y) && !monsterAt(x, y) &&
                                 !(x === player.x && y === player.y)) ? (m.x = x, m.y = y, true) : false;
      if (Math.abs(player.x - m.x) > Math.abs(player.y - m.y)) {
        if (!tryStep(m.x + dx, m.y)) tryStep(m.x, m.y + dy);
      } else {
        if (!tryStep(m.x, m.y + dy)) tryStep(m.x + dx, m.y);
      }
    }
  }

  // ---------- 绘制 ----------
  function visible(x, y) {
    return Math.abs(x - player.x) <= 6 && Math.abs(y - player.y) <= 6;
  }

  function draw() {
    ctx.clearRect(0, 0, cv.width, cv.height);
    for (let y = 0; y < H; y++) {
      for (let x = 0; x < W; x++) {
        const vis = visible(x, y);
        if (vis) seen[y][x] = true;
        if (!seen[y][x]) continue;
        const px = x * S, py = y * S;
        if (map[y][x] === TILE_WALL) {
          ctx.fillStyle = vis ? '#252b34' : '#1a1f26';
        } else {
          ctx.fillStyle = vis ? '#1b222b' : '#171c23';
        }
        ctx.fillRect(px, py, S, S);
        if (vis && map[y][x] === TILE_WALL) {
          ctx.fillStyle = '#2e3641';
          ctx.fillRect(px + 1, py + 1, S - 2, 2);
        }
      }
    }
    // 台阶
    if (seen[stairs.y][stairs.x]) {
      ctx.fillStyle = '#4a4f57';
      ctx.fillRect(stairs.x * S + 3, stairs.y * S + S - 7, S - 6, 4);
    }
    // 怪物
    for (const m of monsters) {
      if (!visible(m.x, m.y)) continue;
      ctx.fillStyle = '#e8710a';
      ctx.beginPath();
      ctx.arc(m.x * S + S / 2, m.y * S + S / 2, S * 0.32, 0, Math.PI * 2);
      ctx.fill();
    }
    // 玩家
    ctx.fillStyle = over ? '#8a3d3d' : '#2fbf9c';
    ctx.fillRect(player.x * S + 3, player.y * S + 3, S - 6, S - 6);
  }

  function syncHud() {
    $hp.textContent = player.hp;
    $depth.textContent = depth;
    $gold.textContent = player.gold;
    $hp.style.color = player.hp <= 3 ? '#e8710a' : '';
  }

  // ---------- 输入 ----------
  const DIRS = {u: [0, -1], d: [0, 1], l: [-1, 0], r: [1, 0]};
  document.querySelectorAll('.pad button[data-d]').forEach(b => {
    b.addEventListener('click', () => {
      const d = DIRS[b.dataset.d];
      move(d[0], d[1]);
    });
  });
  addEventListener('keydown', e => {
    const m = {ArrowUp: 'u', ArrowDown: 'd', ArrowLeft: 'l', ArrowRight: 'r',
               w: 'u', s: 'd', a: 'l', d: 'r'}[e.key];
    if (m) { e.preventDefault(); const d = DIRS[m]; move(d[0], d[1]); }
  });
  let sx = 0, sy = 0;
  cv.addEventListener('touchstart', e => {
    sx = e.touches[0].clientX; sy = e.touches[0].clientY;
  }, {passive: true});
  cv.addEventListener('touchend', e => {
    const dx = e.changedTouches[0].clientX - sx, dy = e.changedTouches[0].clientY - sy;
    if (Math.abs(dx) < 16 && Math.abs(dy) < 16) return;
    if (Math.abs(dx) > Math.abs(dy)) move(dx > 0 ? 1 : -1, 0);
    else move(0, dy > 0 ? 1 : -1);
  }, {passive: true});

  // ---------- 启动 ----------
  depth = 1; over = false;
  genMap();
  syncHud();
  draw();
})();
