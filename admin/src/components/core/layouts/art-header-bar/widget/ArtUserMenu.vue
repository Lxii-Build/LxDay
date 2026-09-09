<!-- 用户菜单 -->
<template>
  <ElPopover
    ref="userMenuPopover"
    placement="bottom-end"
    :width="240"
    :hide-after="0"
    :offset="10"
    trigger="click"
    :show-arrow="false"
    popper-class="user-menu-popover"
    popper-style="padding: 5px 16px;"
  >
    <template #reference>
      <button
        type="button"
        class="mr-5 flex size-11 items-center justify-center rounded-full border-0 bg-transparent p-0 max-sm:mr-[16px]"
        :aria-label="$t('topBar.user.menu')"
      >
        <img class="size-8.5 rounded-full max-sm:size-8" src="@imgs/common/logo.webp" alt="" />
      </button>
    </template>
    <template #default>
      <div class="pt-3">
        <div class="flex-c pb-1 px-0">
          <img
            class="w-10 h-10 mr-3 ml-0 overflow-hidden rounded-full float-left"
            src="@imgs/common/logo.webp"
          />
          <div class="w-[calc(100%-60px)] h-full">
            <span class="block text-sm font-medium text-g-800 truncate">{{
              userInfo.userName
            }}</span>
            <span class="block mt-0.5 text-xs text-g-500 truncate">{{ userInfo.email }}</span>
          </div>
        </div>
        <ul class="py-4 mt-3 border-t border-g-300/80">
          <li>
            <button type="button" class="btn-item" @click="goPage('/change-credentials')">
              <ArtSvgIcon icon="ri:lock-password-line" aria-hidden="true" />
              <span>{{ $t('topBar.user.userCenter') }}</span>
            </button>
          </li>
          <li>
            <button type="button" class="btn-item" @click="toRepo()">
              <ArtSvgIcon icon="ri:github-line" aria-hidden="true" />
              <span>{{ $t('topBar.user.repo') }}</span>
            </button>
          </li>
          <div class="w-full h-px my-2 bg-g-300/80"></div>
          <button type="button" class="log-out" @click="loginOut">
            {{ $t('topBar.user.logout') }}
          </button>
        </ul>
      </div>
    </template>
  </ElPopover>
</template>

<script setup lang="ts">
  import { useI18n } from 'vue-i18n'
  import { useRouter } from 'vue-router'
  import { ElMessageBox } from 'element-plus'
  import { useUserStore } from '@/store/modules/user'
  import { WEB_LINKS } from '@/utils/constants'
  import { openExternalLink } from '@/utils/navigation/jump'

  defineOptions({ name: 'ArtUserMenu' })

  const router = useRouter()
  const { t } = useI18n()
  const userStore = useUserStore()

  const { getUserInfo: userInfo } = storeToRefs(userStore)
  const userMenuPopover = ref()

  /**
   * 页面跳转
   * @param {string} path - 目标路径
   */
  const goPage = (path: string): void => {
    router.push(path)
  }

  /**
   * 打开项目仓库页面
   */
  const toRepo = (): void => {
    openExternalLink(WEB_LINKS.REPO)
  }

  /**
   * 用户登出确认
   */
  const loginOut = (): void => {
    closeUserMenu()
    setTimeout(() => {
      ElMessageBox.confirm(t('common.logOutTips'), t('common.tips'), {
        confirmButtonText: t('common.confirm'),
        cancelButtonText: t('common.cancel'),
        customClass: 'login-out-dialog'
      }).then(() => {
        userStore.logOut()
      })
    }, 200)
  }

  /**
   * 关闭用户菜单弹出层
   */
  const closeUserMenu = (): void => {
    setTimeout(() => {
      userMenuPopover.value.hide()
    }, 100)
  }
</script>

<style scoped>
  @reference '@styles/core/tailwind.css';

  @layer components {
    .btn-item {
      @apply flex min-h-11 w-full items-center border-0 bg-transparent p-2 mb-3 select-none rounded-md cursor-pointer last:mb-0 text-left;

      span {
        @apply text-sm;
      }

      .art-svg-icon {
        @apply mr-2 text-base;
      }

      &:hover {
        background-color: var(--art-gray-200);
      }
    }
  }

  .log-out {
    @apply min-h-11 w-full py-1.5
    mt-5
    text-xs
    text-center
    bg-transparent
    cursor-pointer
    border
    border-g-400
    rounded-md;

    transition:
      box-shadow 200ms,
      background-color 200ms,
      color 200ms;

    &:hover {
      @apply shadow-xl;
    }
  }
</style>
