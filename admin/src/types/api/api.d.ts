/**
 * API 接口类型定义模块
 *
 * 提供林曦日记运营后台所有后端接口的类型定义。
 * 后端响应信封：{ code:200, msg:'success', data:... }
 *
 * @module types/api/api
 */

declare namespace Api {
  /** 通用类型 */
  namespace Common {
    /** 分页参数 */
    interface PaginationParams {
      /** 当前页码 */
      current: number
      /** 每页条数 */
      size: number
      /** 总条数 */
      total: number
    }

    /** 通用搜索参数 */
    type CommonSearchParams = Pick<PaginationParams, 'current' | 'size'>

    /** 分页响应基础结构（对齐后端 records/total/current/size） */
    interface PaginatedResponse<T = any> {
      records: T[]
      current: number
      size: number
      total: number
    }
  }

  /** 认证类型 */
  namespace Auth {
    /** 登录参数 */
    interface LoginParams {
      username: string
      password: string
    }

    /** 登录响应 */
    interface LoginResponse {
      token: string
      refreshToken: string
      must_change: boolean
    }

    /** 用户信息 */
    interface UserInfo {
      userId: number
      userName: string
      roles: string[]
      buttons: string[]
      avatar?: string
      email?: string | null
      must_change?: boolean
    }

    /** 首次修改凭据参数 */
    interface ChangeCredentialsParams {
      old_password: string
      username?: string
      password?: string
      email?: string
    }
  }

  /** 运营后台业务类型 */
  namespace Admin {
    /** 数据看板统计 */
    interface DashboardStats {
      users: number
      pairs: number
      todos: number
      diaries: number
      new_users_7d: number
      daily_new: { date: string; count: number }[]
    }

    /** 用户列表项 */
    interface UserItem {
      id: number
      username: string | null
      email: string | null
      nickname: string
      avatar_url: string | null
      gender: number
      signature: string | null
      birthday: string | null
      anniversary: string | null
      status: number
      created_at: string
    }
    type UserList = Api.Common.PaginatedResponse<UserItem>
    type UserSearchParams = Partial<Api.Common.CommonSearchParams & { keyword: string }>

    /** 绑定关系列表项 */
    interface PairItem {
      id: number
      user_a_id: number
      user_b_id: number
      name_a: string
      name_b: string
      status: number
      invite_code: string
      created_at: string
    }
    type PairList = Api.Common.PaginatedResponse<PairItem>

    /** 待办列表项 */
    interface TodoItem {
      id: number
      pair_id: number
      creator_id: number
      creator_name: string
      assignee_id: number
      assignee_name: string
      title: string
      note: string
      remind_enabled: boolean
      remind_type: number
      repeat_type: number
      remind_at: string
      status: number
      created_at: string
    }
    type TodoList = Api.Common.PaginatedResponse<TodoItem>
    type TodoSearchParams = Partial<Api.Common.CommonSearchParams & { keyword: string }>

    /** 日记列表项 */
    interface DiaryItem {
      id: number
      pair_id: number
      author_id: number
      author_name: string
      title: string
      diary_date: string
      created_at: string
    }
    type DiaryList = Api.Common.PaginatedResponse<DiaryItem>

    /** APP 版本项 */
    interface AppVersionItem {
      id: number
      platform: string
      version_name: string
      version_code: number
      apk_url: string
      notes: string
      force_update: boolean
      status: number
      created_at: string
    }
    type AppVersionList = Api.Common.PaginatedResponse<AppVersionItem>
    type AppVersionSearchParams = Partial<Api.Common.CommonSearchParams & { platform: string }>
    interface AppVersionCreateParams {
      platform: string
      version_name: string
      version_code: number
      apk_url?: string
      notes?: string
      force_update?: boolean
    }

    /** 通知模板 */
    interface NotifyTemplate {
      id: number
      code: string
      title: string
      body: string
      enabled: number
      updated_at: string
    }

    /** 通知下发记录 */
    interface NotifyRecord {
      id: number
      template_code: string
      title: string
      body: string
      target: string
      sent_count: number
      created_at: string
    }
    type NotifyRecordList = Api.Common.PaginatedResponse<NotifyRecord>
    interface NotifySendParams {
      title: string
      body: string
      target?: string
      template_code?: string
    }

    /** 审计日志 */
    interface AuditLog {
      id: number
      admin_id: number
      admin_name: string
      action: string
      detail: string
      ip: string
      created_at: string
    }
    type AuditLogList = Api.Common.PaginatedResponse<AuditLog>

    /** 管理员 */
    interface AdminItem {
      id: number
      username: string
      email: string | null
      role: string
      must_change: boolean
      status: number
    }
    interface AdminCreateParams {
      username: string
      password: string
      role?: string
      email?: string
    }

    /** 系统设置键值 map */
    type Settings = Record<string, string>
  }
}
