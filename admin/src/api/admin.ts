import request from '@/utils/http'

/**
 * 林曦日记运营后台 API
 * 统一前缀 /api/admin，响应信封 {code:200,msg,data}
 */

// ---------- 数据看板 ----------
export function fetchDashboardStats() {
  return request.get<Api.Admin.DashboardStats>({ url: '/api/admin/stats' })
}

// ---------- 用户管理 ----------
export function fetchUserList(params: Api.Admin.UserSearchParams) {
  return request.get<Api.Admin.UserList>({ url: '/api/admin/users', params })
}

export function updateUserStatus(id: number, status: number) {
  return request.put<{ ok: boolean }>({
    url: `/api/admin/users/${id}/status`,
    data: { status }
  })
}

// ---------- 绑定关系 ----------
export function fetchPairList(params: Api.Common.CommonSearchParams) {
  return request.get<Api.Admin.PairList>({ url: '/api/admin/pairs', params })
}

export function unbindPair(id: number) {
  return request.post<{ ok: boolean }>({ url: `/api/admin/pairs/${id}/unbind` })
}

// ---------- 内容审核 ----------
export function fetchTodoList(params: Api.Admin.TodoSearchParams) {
  return request.get<Api.Admin.TodoList>({ url: '/api/admin/todos', params })
}

export function deleteTodo(id: number) {
  return request.del<{ ok: boolean }>({ url: `/api/admin/todos/${id}` })
}

export function fetchDiaryList(params: Api.Common.CommonSearchParams) {
  return request.get<Api.Admin.DiaryList>({ url: '/api/admin/diaries', params })
}

export function deleteDiary(id: number) {
  return request.del<{ ok: boolean }>({ url: `/api/admin/diaries/${id}` })
}

// ---------- APP 版本发布 ----------
export function fetchAppVersionList(params: Api.Admin.AppVersionSearchParams) {
  return request.get<Api.Admin.AppVersionList>({ url: '/api/admin/app-versions', params })
}

export function createAppVersion(data: Api.Admin.AppVersionCreateParams) {
  return request.post<{ id: number }>({ url: '/api/admin/app-versions', data })
}

export function updateAppVersionStatus(id: number, status: number) {
  return request.put<{ ok: boolean }>({
    url: `/api/admin/app-versions/${id}/status`,
    data: { status }
  })
}

export function deleteAppVersion(id: number) {
  return request.del<{ ok: boolean }>({ url: `/api/admin/app-versions/${id}` })
}

// ---------- 通知 ----------
export function fetchNotifyTemplates() {
  return request.get<Api.Admin.NotifyTemplate[]>({ url: '/api/admin/notify-templates' })
}

export function upsertNotifyTemplate(data: {
  code: string
  title: string
  body: string
  enabled: number
}) {
  return request.put<{ ok: boolean }>({ url: '/api/admin/notify-templates', data })
}

export function sendNotify(data: Api.Admin.NotifySendParams) {
  return request.post<{ sent: number }>({ url: '/api/admin/notify', data })
}

export function fetchNotifyRecords(params: Api.Common.CommonSearchParams) {
  return request.get<Api.Admin.NotifyRecordList>({ url: '/api/admin/notify-records', params })
}

// ---------- 系统设置 ----------
export function fetchSettings() {
  return request.get<Api.Admin.Settings>({ url: '/api/admin/settings' })
}

export function updateSettings(data: Api.Admin.Settings) {
  return request.put<{ ok: boolean }>({ url: '/api/admin/settings', data })
}

// ---------- 审计日志 ----------
export function fetchAuditLogs(params: Api.Common.CommonSearchParams) {
  return request.get<Api.Admin.AuditLogList>({ url: '/api/admin/audit-logs', params })
}

// ---------- 网络日志 ----------
export function fetchNetworkLogs(params: Api.Admin.NetworkLogSearchParams) {
  return request.get<Api.Admin.NetworkLogList>({ url: '/api/admin/network-logs', params })
}

// ---------- 管理员管理 ----------
export function fetchAdmins() {
  return request.get<Api.Admin.AdminItem[]>({ url: '/api/admin/admins' })
}

export function createAdmin(data: Api.Admin.AdminCreateParams) {
  return request.post<{ id: number }>({ url: '/api/admin/admins', data })
}
