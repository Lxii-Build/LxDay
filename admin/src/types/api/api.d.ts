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
    interface UserUpdateParams {
      email?: string | null
      nickname: string
      gender: number
      signature?: string | null
      birthday?: string | null
    }

    /** 绑定关系列表项 */
    interface PairItem {
      id: number
      user_a_id: number
      user_b_id: number
      name_a: string
      name_b: string
      status: number
      /**
       * 是否存在挂起的邀请码。
       * 刻意**不**下发邀请码本身：它是"成为某人伴侣"的凭据，
       * 任何拿到它的管理员都能绑定陌生用户，进而合法读取对方
       * 相册、状态历史与待办（那些接口本身早已收敛到超管）。
       */
      has_invite: boolean
      anniversary: string | null
      created_at: string
    }
    type PairList = Api.Common.PaginatedResponse<PairItem>
    type PairSearchParams = Partial<Api.Common.CommonSearchParams & { keyword: string }>
    interface PairUpdateParams {
      anniversary_date?: string | null
    }

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
      weekdays: number
      remind_at: string | null
      status: number
      created_at: string
    }
    type TodoList = Api.Common.PaginatedResponse<TodoItem>
    type TodoSearchParams = Partial<Api.Common.CommonSearchParams & { keyword: string }>
    interface TodoUpdateParams {
      assignee_id: number
      title: string
      note: string
      remind_at?: string | null
      remind_type: number
      repeat_type: number
      weekdays: number
      remind_enabled: boolean
    }

    /** 相册列表项（后台管理用） */
    interface AlbumItem {
      id: number
      pair_id: number
      name: string
      created_at: string
      /** "昵称A & 昵称B" */
      couple: string
      photo_count: number
      size_bytes: number
    }
    type AlbumList = Api.Common.PaginatedResponse<AlbumItem>
    type AlbumSearchParams = Partial<
      Api.Common.CommonSearchParams & { keyword: string; pair_id: number }
    >

    /** 某对情侣的磁盘占用 */
    interface StorageUsageItem {
      pair_id: number
      couple: string
      photo_count: number
      size_bytes: number
      recycled_count: number
      recycled_bytes: number
    }

    /** 磁盘占用统计 */
    interface StorageStats {
      pairs: StorageUsageItem[]
      total: {
        photo_count: number
        size_bytes: number
        recycled_count: number
        recycled_bytes: number
        /** uploads 目录真实占用（含缩略图与预览图） */
        disk_bytes: number
        disk_file_count: number
      }
      retention: {
        recycle_bin_days: number
        status_history_days: number
        network_log_days: number
      }
    }

    /** 运行参数的一项元信息 */
    interface RuntimeSettingMeta {
      key: string
      group: string
      /** int | int64 | bool | bytes */
      kind: string
      min: number
      max: number
      label: string
      /**
       * 该项是否只有超管可写（retention.* 与 security.* 全部为 true）。
       *
       * 前端应据此把非超管的输入框置灰。但**置灰只是提示**：
       * 真正的拦截在服务端 handleAdminUpdateSettings，非超管改这些键一律 403。
       * retention.recycle_bin_days 尤其危险——调小它会让定时清理真删磁盘上的照片。
       */
      super: boolean
    }

    /** GET /runtime-settings 的响应 */
    interface RuntimeSettingsResp {
      values: Record<string, string>
      /** 默认值，供「一键恢复默认」填回 */
      defaults: Record<string, string>
      meta: RuntimeSettingMeta[]
    }

    /**
     * 相册照片列表项（后台审核用）
     *
     * 服务端 Store.ListPhotosAll 刻意**不返回任何图片 URL**：管理员没有用户 token，
     * 本就读不了 `/media/<id>` 鉴权代理，返回 URL 只会凭空多出一条隐私泄露面。
     * 故本页只做元数据审核，不显示缩略图。
     */
    interface PhotoItem {
      id: number
      pair_id: number
      /** 所属相册；0 = 未归类 */
      album_id: number
      uploader_id: number
      uploader_name: string
      caption: string
      width: number
      height: number
      size_bytes: number
      mime: string
      /** EXIF 拍摄时间，解析不到时服务端回 null */
      taken_at: string | null
      /** 1 正常 / 2 回收站 */
      status: number
      created_at: string | null
    }
    type PhotoList = Api.Common.PaginatedResponse<PhotoItem>
    type PhotoSearchParams = Partial<
      Api.Common.CommonSearchParams & {
        /** 关键词，服务端只搜 caption */
        keyword: string
        /** 按绑定关系筛选；0 或不传表示不筛选 */
        pair_id: number
      }
    >
    interface PhotoUpdateParams {
      caption: string
    }

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
    interface AppVersionUpdateParams {
      version_name: string
      apk_url: string
      notes: string
      force_update: boolean
    }

    interface AlbumUpdateParams {
      name: string
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
    type AuditLogSearchParams = Partial<
      Api.Common.CommonSearchParams & {
        /** 操作人用户名 */
        admin_name: string
        /** 动作 */
        action: string
        /** 起始时间 YYYY-MM-DD HH:mm:ss */
        start: string
        /** 结束时间 YYYY-MM-DD HH:mm:ss */
        end: string
      }
    >

    /** 网络请求日志 */
    interface NetworkLog {
      id: number
      method: string
      path: string
      status: number
      latency_ms: number
      ip: string
      ua: string
      request_id: string
      created_at: string
    }
    type NetworkLogList = Api.Common.PaginatedResponse<NetworkLog>
    type NetworkLogSearchParams = Partial<
      Api.Common.CommonSearchParams & { method: string; path: string; status: number }
    >

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
    /**
     * 管理员列表响应
     * 服务端目前返回裸数组；为兼容后续加分页，这里允许两种形态，前端统一归一化。
     */
    type AdminListResponse = AdminItem[] | { list: AdminItem[]; total: number }
    type AdminSearchParams = Partial<Api.Common.CommonSearchParams>

    /** 系统设置键值 map */
    type Settings = Record<string, string>
  }
}
