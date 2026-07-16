import api from '@/api'

export const ingestApi = {
  ingestFile(file, overwrite = false) {
    const formData = new FormData()
    formData.append('file', file)
    formData.append('overwrite', overwrite)
    return api.post('/rag/ingest/file', formData, {
      headers: {
        'Content-Type': 'multipart/form-data'
      },
      timeout: 300000
    })
  },

  //上传前同名文件预检：返回 { duplicate, documentId, fileName, createdAt }
  checkDuplicate(fileName) {
    return api.get('/rag/ingest/check', { params: { fileName } })
  }
}
