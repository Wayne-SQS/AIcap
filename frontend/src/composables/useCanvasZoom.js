import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'

const clamp = (value, min, max) => Math.min(max, Math.max(min, value))

export function useCanvasZoom({
  viewport,
  canvas,
  shell,
  minZoom = 0.6,
  maxZoom = 2,
  fitMinZoom = minZoom,
  fitPadding = 0,
  fitAlign = 'start',
  fillViewportWidth = false,
  minContentWidth = 0,
  initialMode = 'fit',
  renderMode = 'transform'
}) {
  const zoom = ref(1)
  const zoomMode = ref('fit')
  const logicalWidth = ref(0)
  const logicalHeight = ref(0)
  let resizeObserver
  let resizeFrame = 0

  const usesDimensions = renderMode === 'dimensions'
  const scaledSize = (size) => Math.round(size * zoom.value)
  const canvasStyle = computed(() => usesDimensions
    ? {
        width: logicalWidth.value ? `${scaledSize(logicalWidth.value)}px` : undefined,
        height: logicalHeight.value ? `${scaledSize(logicalHeight.value)}px` : undefined,
        transform: 'none',
        transformOrigin: '0 0'
      }
    : {
        width: logicalWidth.value ? `${logicalWidth.value}px` : undefined,
        transform: `scale(${zoom.value})`,
        transformOrigin: '0 0'
      })
  const shellStyle = computed(() => ({
    width: `${Math.max(viewport.value?.clientWidth || 0, (usesDimensions ? scaledSize(logicalWidth.value) : logicalWidth.value * zoom.value) + (zoomMode.value === 'fit' ? fitPadding * 2 : 0))}px`,
    height: `${Math.max(viewport.value?.clientHeight || 0, (usesDimensions ? scaledSize(logicalHeight.value) : logicalHeight.value * zoom.value) + (zoomMode.value === 'fit' ? fitPadding * 2 : 0))}px`,
    paddingLeft: zoomMode.value === 'fit' && viewport.value
      ? `${fitAlign === 'start' ? fitPadding : Math.max(fitPadding, (viewport.value.clientWidth - logicalWidth.value * zoom.value) / 2)}px`
      : '0px',
    paddingTop: zoomMode.value === 'fit' && viewport.value
      ? `${fitAlign === 'start' ? fitPadding : Math.max(fitPadding, (viewport.value.clientHeight - logicalHeight.value * zoom.value) / 2)}px`
      : '0px'
  }))

  function measure() {
    const node = canvas.value
    if (!node) return false
    const view = viewport.value
    const filledWidth = fillViewportWidth
      ? Math.max(minContentWidth, view?.clientWidth || 0)
      : 0
    if (filledWidth && node.style.width !== `${filledWidth}px`) {
      node.style.width = `${filledWidth}px`
    }
    const rect = node.getBoundingClientRect()
    const scale = zoom.value || 1
    const renderedWidth = Number.isFinite(node.offsetWidth) ? node.offsetWidth : rect.width
    const renderedHeight = Number.isFinite(node.offsetHeight) ? node.offsetHeight : rect.height
    const intrinsicWidth = usesDimensions
      ? renderedWidth / scale
      : Math.max(renderedWidth, node.scrollWidth || 0)
    const width = filledWidth || intrinsicWidth
    logicalWidth.value = width
    logicalHeight.value = usesDimensions
      ? renderedHeight / scale
      : Math.max(renderedHeight, node.scrollHeight || 0)
    return logicalWidth.value > 0 && logicalHeight.value > 0
  }

  function applyZoom(value, focus = null, mode = 'manual') {
    const view = viewport.value
    const node = canvas.value
    if (!view || !node) return
    const oldZoom = zoom.value
    const nextZoom = clamp(Number(value) || 1, minZoom, maxZoom)
    zoomMode.value = mode
    if (Math.abs(nextZoom - oldZoom) < 0.0001) return
    const viewRect = view.getBoundingClientRect()
    const oldRect = node.getBoundingClientRect()
    const clientX = focus?.clientX ?? viewRect.left + view.clientWidth / 2
    const clientY = focus?.clientY ?? viewRect.top + view.clientHeight / 2
    const logicalX = (clientX - oldRect.left) / oldZoom
    const logicalY = (clientY - oldRect.top) / oldZoom
    const focusX = clientX - viewRect.left
    const focusY = clientY - viewRect.top

    zoom.value = nextZoom
    nextTick(() => {
      const newRect = node.getBoundingClientRect()
      const pointX = newRect.left - viewRect.left + logicalX * nextZoom
      const pointY = newRect.top - viewRect.top + logicalY * nextZoom
      view.scrollLeft = Math.max(0, view.scrollLeft + pointX - focusX)
      view.scrollTop = Math.max(0, view.scrollTop + pointY - focusY)
    })
  }

  function setZoomPercent(percent) {
    const value = clamp(Number(percent) / 100, minZoom, maxZoom)
    applyZoom(value)
    return Math.round(value * 100)
  }

  function zoomIn() { setZoomPercent(zoom.value * 100 + 10) }
  function zoomOut() { setZoomPercent(zoom.value * 100 - 10) }
  function resetZoom() {
    measure()
    applyZoom(1)
  }

  function fitToCanvas() {
    const view = viewport.value
    if (!view || !measure()) return
    const padding = fitPadding
    const availableWidth = Math.max(1, view.clientWidth - padding * 2)
    const availableHeight = Math.max(1, view.clientHeight - padding * 2)
    zoomMode.value = 'fit'
    zoom.value = clamp(Math.min(
      availableWidth / logicalWidth.value,
      availableHeight / logicalHeight.value
    ), fitMinZoom, maxZoom)
    nextTick(() => {
      const width = logicalWidth.value * zoom.value
      view.scrollLeft = fitAlign === 'start'
        ? 0
        : Math.max(0, (width - view.clientWidth) / 2)
      view.scrollTop = 0
    })
  }

  function scheduleResize() {
    cancelAnimationFrame(resizeFrame)
    resizeFrame = requestAnimationFrame(() => {
      measure()
      if (zoomMode.value === 'fit') fitToCanvas()
    })
  }

  onMounted(async () => {
    await nextTick()
    measure()
    if (initialMode === 'fit') fitToCanvas()
    else resetZoom()
    resizeObserver = new ResizeObserver(scheduleResize)
    if (viewport.value) resizeObserver.observe(viewport.value)
  })

  onBeforeUnmount(() => {
    cancelAnimationFrame(resizeFrame)
    resizeObserver?.disconnect()
  })

  return { zoom, zoomMode, minZoom, maxZoom, canvasStyle, shellStyle, zoomIn, zoomOut, setZoomPercent, resetZoom, fitToCanvas, applyZoom, measure }
}
