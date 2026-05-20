import api from '@/api'

export const ingestApi = {
  ingestFile(file) {
    const formData = new FormData()
    formData.append('file', file)
    return api.post('/ingest/file', formData, {
      headers: {
        'Content-Type': 'multipart/form-data'
      },
      timeout: 120000
    })
  },

  ingest(filePath, category = '') {
    return api.post('/ingest', { filePath, category })
  }
}
