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
export function fetchPairList(params: Api.Admin.PairSearchParams) {
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

/**
 * 相册照片列表（仅超级管理员可用）
 *
 * 只回元数据，不含图片 URL——相册是全站最私密的内容，后台只做违规处置不做浏览。
 */
export function fetchPhotoList(params: Api.Admin.PhotoSearchParams) {
  return request.get<Api.Admin.PhotoList>({ url: '/api/admin/photos', params })
}

/** 删除照片：软删进用户回收站，用户可自行恢复，不删磁盘文件 */
export function deletePhoto(id: number) {
  return request.del<{ ok: boolean }>({ url: `/api/admin/photos/${id}` })
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

export function deleteNotifyTemplate(id: number) {
  return request.del<{ ok: boolean }>({ url: `/api/admin/notify-templates/${id}` })
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

/**
 * 站点展示信息（名称/LOGO/描述）。
 *
 * 与 fetchSettings 分开：后者含 SMTP 与存储密钥，本轮已收敛到超管；
 * 普通 admin 用它取站点名会吃 403，且首登强制改密期间任何人都拿不到
 * （改密页顶栏会因此空白并在控制台刷 403）。
 */
export function fetchSiteInfo() {
  return request.get<Record<string, string>>({ url: '/api/admin/site-info' })
}

export function updateSettings(data: Api.Admin.Settings) {
  return request.put<{ ok: boolean }>({ url: '/api/admin/settings', data })
}

/** 发送一封 SMTP 测试邮件，用于验证邮件配置是否可用 */
export function sendSmtpTest(to: string) {
  return request.post<{ ok: boolean }>({ url: '/api/admin/settings/smtp-test', data: { to } })
}

// ---------- 审计日志 ----------
export function fetchAuditLogs(params: Api.Admin.AuditLogSearchParams) {
  return request.get<Api.Admin.AuditLogList>({ url: '/api/admin/audit-logs', params })
}

// ---------- 网络日志 ----------
export function fetchNetworkLogs(params: Api.Admin.NetworkLogSearchParams) {
  return request.get<Api.Admin.NetworkLogList>({ url: '/api/admin/network-logs', params })
}

// ---------- 管理员管理 ----------
export function fetchAdmins(params?: Api.Admin.AdminSearchParams) {
  return request.get<Api.Admin.AdminListResponse>({ url: '/api/admin/admins', params })
}

export function createAdmin(data: Api.Admin.AdminCreateParams) {
  return request.post<{ id: number }>({ url: '/api/admin/admins', data })
}

/** 修改管理员角色（admin / super） */
export function updateAdminRole(id: number, role: string) {
  return request.put<{ ok: boolean }>({ url: `/api/admin/admins/${id}`, data: { role } })
}

/** 启用(1)/禁用(2) 管理员 */
export function updateAdminStatus(id: number, status: number) {
  return request.put<{ ok: boolean }>({ url: `/api/admin/admins/${id}/status`, data: { status } })
}

/** 重置管理员密码 */
export function resetAdminPassword(id: number, password: string) {
  return request.post<{ ok: boolean }>({
    url: `/api/admin/admins/${id}/reset-password`,
    data: { password }
  })
}

export function deleteAdmin(id: number) {
  return request.del<{ ok: boolean }>({ url: `/api/admin/admins/${id}` })
}

/* ==================== 相册管理与磁盘统计（0821 新增，仅超管） ==================== */

/** 相册列表（按 pair 聚合，含张数与占用空间） */
export function fetchAlbumList(params: Api.Admin.AlbumSearchParams) {
  return request.get<Api.Admin.AlbumList>({ url: '/api/admin/albums', params })
}

/** 删相册（软删，其中照片退回「未归类」） */
export function deleteAlbum(id: number) {
  return request.del<{ ok: boolean }>({ url: `/api/admin/albums/${id}` })
}

/** 磁盘占用统计：各 pair 的照片数/字节数 + 全站合计 + 真实磁盘占用 */
export function fetchStorageStats() {
  return request.get<Api.Admin.StorageStats>({ url: '/api/admin/storage-stats' })
}

/** 清空某对情侣的回收站（**真删磁盘文件，不可恢复**） */
export function purgeRecycleBin(pairId: number) {
  return request.post<{ purged: number; freed_bytes: number }>({
    url: `/api/admin/pairs/${pairId}/purge-recycle-bin`
  })
}

/** 运行参数（相册配额/保留期/限流/互动冷却）。不含密钥，普通管理员可读 */
export function fetchRuntimeSettings() {
  return request.get<Api.Admin.RuntimeSettingsResp>({ url: '/api/admin/runtime-settings' })
}
