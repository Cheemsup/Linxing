import api from '@/api'

export const ingestApi = {
  ingestFile(file) {
    const formData = new FormData()
    formData.append('file', file)
    return api.post('/rag/ingest/file', formData, {
      headers: {
        'Content-Type': 'multipart/form-data'
      },
      timeout: 300000
    })
  }
}
