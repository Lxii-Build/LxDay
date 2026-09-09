import type { App } from 'vue'
import { setupAuthDirective, type AuthDirective } from './core/auth'
import { setupRolesDirective, type RolesDirective } from './core/roles'

export function setupGlobDirectives(app: App) {
  setupAuthDirective(app) // 权限指令
  setupRolesDirective(app) // 角色权限指令
}

export type { AuthDirective, RolesDirective }
