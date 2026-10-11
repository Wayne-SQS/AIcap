import { onMounted, ref } from 'vue'
import { storiesApi } from '@/api/stories'

export function useReviewStoryChoices() {
  const stories = ref([])
  const loadingStories = ref(true)
  const storyError = ref('')
  let active = true

  async function loadStories() {
    loadingStories.value = true
    storyError.value = ''
    try {
      const rows = await storiesApi.list()
      if (!Array.isArray(rows) || rows.some(story => typeof story.id !== 'string' || typeof story.title !== 'string')) throw new Error()
      if (active) stories.value = rows
    } catch {
      if (active) { stories.value = []; storyError.value = '故事目录加载失败，请重试。' }
    } finally {
      if (active) loadingStories.value = false
    }
  }

  onMounted(loadStories)
  return { stories, loadingStories, storyError, loadStories, stopStoryChoices: () => { active = false } }
}
