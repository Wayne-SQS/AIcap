<script setup>
import OfficeAvatar from './OfficeAvatar.vue'
defineProps({ desks: { type: Array, required: true }, active: { type: String, default: '' }, latest: { type: String, default: '' }, paused: Boolean })
defineEmits(['select', 'activity'])
</script>
<template>
  <section class="pixel-room" :class="{ 'motion-paused': paused }" aria-label="像素办公室场景">
    <svg class="room-shell" viewBox="0 0 1040 660" preserveAspectRatio="none" shape-rendering="crispEdges" aria-hidden="true">
      <defs>
        <pattern id="office-floor" width="104" height="52" patternUnits="userSpaceOnUse"><rect width="104" height="52" fill="#dfc6a2"/><path d="M0 0h104v2H0zM0 26h104v2H0zM20 2h2v24h-2zM72 28h2v24h-2z" fill="#caac87"/><path d="M28 8h42v2H28zm-20 26h36v2H8zm58 9h28v2H66z" fill="#e9d4b5"/></pattern>
        <pattern id="office-wall" width="48" height="48" patternUnits="userSpaceOnUse"><rect width="48" height="48" fill="#e5e8ce"/><path d="M0 47h48M47 0v48" stroke="#d8ddbf"/></pattern>
        <g id="office-plant"><path d="M-12 0h24v6H-12zm3 6H9v18H-9z" fill="#b98562"/><path d="M-5 6h4v14h-4z" fill="#d2a07c"/><path d="M-2-30h4V0h-4zM-16-22h14v12h-14zm18-12h14v14H2zM-20-10h18v8h-18zm22-4h16v10H2z" fill="#6e8c52"/><path d="M-12-24h8v10h-8zM4-36h8v12H4z" fill="#93a773"/></g>
        <g id="office-books"><path d="M0 0h9v28H0z" fill="#91a485"/><path d="M11 4h8v24h-8z" fill="#c18a68"/><path d="M21 0h10v28H21z" fill="#94afb1"/><path d="M34 6h9v22h-9z" fill="#d3b46d"/><path d="M2 6h5v2H2zm11 5h4v2h-4zm10-5h6v2h-6z" fill="#f5e9cf"/></g>
      </defs>
      <path d="M12 20h1016v622H12z" fill="#788064"/><path d="M20 12h1000v626H20z" fill="#a8ae8e"/>
      <path d="M28 20h984v110H28z" fill="url(#office-wall)"/><path d="M28 130h984v500H28z" fill="url(#office-floor)"/>
      <path d="M28 124h984v10H28z" fill="#879070"/><path d="M28 134h984v7H28z" fill="#b49572"/>
      <path d="M28 20h10v610H28zm974 0h10v610h-10z" fill="#c9cba9"/><path d="M28 620h984v10H28z" fill="#af916d"/>
      <!-- 日光窗与落地的暖色光斑 -->
      <path d="M70 40h190v76H70z" fill="#88785f"/><path d="M76 46h178v64H76z" fill="#f7f1d8"/><path d="M82 52h166v52H82z" fill="#b5cebf"/>
      <path d="M84 84h36V72h22v12h38V66h22v18h44v20H84z" fill="#d6dfc4"/><path d="M84 94h54v-8h24v8h32v-6h52v16H84z" fill="#91ae8a"/>
      <path d="M160 46h6v64h-6zM76 76h178v5H76z" fill="#f7f1d8"/><path d="M62 34h206v8H62z" fill="#7b8263"/>
      <path d="M80 142h172l74 166H122z" fill="#fff5c9" opacity=".17"/>
      <!-- 墙上时钟 -->
      <path d="M306 47h44v44h-44z" fill="#82785e"/><path d="M312 53h32v32h-32z" fill="#f8f2df"/><path d="M327 57h3v15h-3zm0 12h11v3h-11z" fill="#6b775c"/>
      <!-- 书架 -->
      <path d="M796 40h142v84H796z" fill="#9d7d58"/><path d="M802 46h130v70H802z" fill="#c3a37b"/><path d="M802 80h130v6H802z" fill="#8d704f"/>
      <use href="#office-books" x="812" y="51"/><use href="#office-books" x="866" y="86"/>
      <path d="M876 54h36v20h-36z" fill="#e4d7b4"/><path d="M884 59h20v3h-20z" fill="#aca382"/>
      <!-- 公共区的地毯与矮桌 -->
      <path d="M357 260h328v240H357z" fill="#b29671" opacity=".25"/><path d="M351 254h328v240H351z" fill="#97a67d"/><path d="M359 262h312v224H359z" fill="#b5bd96"/>
      <path d="M367 270h296v208H367z" fill="#a5b286"/><path d="M381 284h268v180H381z" fill="none" stroke="#c3c9a6" stroke-width="3"/>
      <path d="M446 347h144v66H446z" fill="#7d7656" opacity=".2"/><path d="M440 337h144v60H440z" fill="#977c57"/><path d="M440 329h144v58H440z" fill="#c8a879"/>
      <path d="M446 335h132v44H446z" fill="#dfc599"/><path d="M455 344h36v20h-36z" fill="#edf0d8"/><path d="M460 349h25v2h-25zm0 6h19v2h-19z" fill="#a0ab8a"/>
      <path d="M542 349h17v17h-17zM559 353h5v9h-5z" fill="#faf2dc"/><path d="M545 352h11v3h-11z" fill="#927452"/>
      <!-- 绿植、饮水角和入口 -->
      <use href="#office-plant" x="72" y="180"/><use href="#office-plant" x="966" y="592"/><use href="#office-plant" x="70" y="584"/>
      <path d="M949 187h34v44h-34z" fill="#dadfcb"/><path d="M953 163h26v25h-26z" fill="#a1c0be"/><path d="M958 167h4v16h-4z" fill="#cce0d7"/><path d="M955 203h22v4h-22z" fill="#a6b59c"/><path d="M963 209h7v3h-7z" fill="#687c65"/>
      <path d="M440 620h154v16H440z" fill="#ead6b6"/><path d="M446 626h142v11H446z" fill="#b59470"/><path d="M455 564h126v43H455z" fill="#9eaa83"/><path d="M463 572h110v27H463z" fill="#bac3a1"/>
    </svg>

    <div class="room-wall-sign" aria-hidden="true"><span>GOOD WORK, TOGETHER.</span><small>爱管理 · AI 团队</small></div>
    <button type="button" class="room-whiteboard" aria-label="打开办公室完整过程记录" @click="$emit('activity')"><span class="whiteboard-pin" aria-hidden="true"></span><strong>办公室白板 <span>↗</span></strong><span>{{ latest || '今天，从一个好想法开始。' }}</span><small>{{ latest ? '查看完整过程记录' : '向本提问，进展会写在这里' }}</small></button>
    <div class="room-rug-letter" aria-hidden="true">MAKE IDEAS<br>HAPPEN<span>一起，把想法变成进展。</span></div>
    <div class="room-welcome" aria-hidden="true">WELCOME</div>

    <button v-for="desk in desks" :key="desk.id" type="button" class="room-desk" :class="[`desk-${desk.id}`, { 'is-selected': active === desk.id, 'is-busy': desk.busy }]" :style="{ '--desk-accent': desk.color }" :aria-label="`打开${desk.name}的${desk.role}`" :aria-expanded="active === desk.id" @click="$emit('select', desk.id)">
      <span class="desk-speech" :class="{ 'is-muted': !desk.busy }">{{ desk.busy ? '正在为你处理…' : desk.hint }}</span>
      <svg class="desk-furniture" viewBox="0 0 200 128" shape-rendering="crispEdges" aria-hidden="true">
        <path d="M10 48h180v58H10z" fill="#9e825c" opacity=".2"/><path d="M10 36h180v58H10z" fill="#8c7252"/><path d="M10 28h180v58H10z" fill="#b7966b"/><path d="M14 32h172v46H14z" fill="#e4cba0"/><path d="M14 80h172v6H14z" fill="#ba9569"/>
        <path d="M54 12h76v38H54z" fill="#4d5f52"/><path d="M58 16h68v29H58z" fill="#263e35"/><path d="M62 20h30v3H62zm0 7h49v3H62zm0 7h22v3H62z" fill="#9ebd8b"/><path d="M87 50h10v5H87zm-9 5h28v4H78z" fill="#596955"/>
        <path d="M62 63h67v12H62z" fill="#9caa90"/><path d="M66 65h58v2H66zm0 5h58v2H66z" fill="#e8ecd6"/>
        <path d="M144 48h13v17h-13zm13 3h5v10h-5z" fill="#f5efda"/><path d="M146 48h9v3h-9z" fill="#94714c"/>
        <path d="M22 48h20v25H22z" :fill="desk.color"/><path d="M26 53h12v2H26zm0 6h12v2H26z" fill="#fbf4de"/>
        <path d="M74 99h54v21H74z" fill="#596d55"/><path d="M79 103h44v17H79z" fill="#80916f"/><path d="M78 122h45v4H78z" fill="#54614c"/>
      </svg>
      <OfficeAvatar :who="desk.id" :size="52" :busy="desk.busy" />
      <span class="desk-nameplate"><strong>{{ desk.name }}</strong><span>{{ desk.role }}</span><i :class="`state-${desk.state}`" aria-hidden="true"></i></span>
      <span class="desk-state">{{ desk.status }}</span>
    </button>
  </section>
</template>
