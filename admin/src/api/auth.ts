import request from '@/utils/http'

/**
 * 管理员登录
 * POST /api/admin/login {username,password} -> {token,refreshToken,must_change}
 */
export function fetchLogin(params: Api.Auth.LoginParams) {
  return request.post<Api.Auth.LoginResponse>({
    url: '/api/admin/login',
    params
  })
}

/**
 * 获取当前管理员信息
 * GET /api/admin/user/info
 */
export function fetchGetUserInfo() {
  return request.get<Api.Auth.UserInfo>({
    url: '/api/admin/user/info'
  })
}

/**
 * 首次修改凭据（含改用户名/密码/邮箱）
 * POST /api/admin/change-credentials
 */
export function fetchChangeCredentials(params: Api.Auth.ChangeCredentialsParams) {
  return request.post<{ ok: boolean; token?: string; refreshToken?: string }>({
    url: '/api/admin/change-credentials',
    params
  })
}
