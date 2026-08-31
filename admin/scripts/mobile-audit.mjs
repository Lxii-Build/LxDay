/**
 * 后台移动端适配验证（管理员 Q45=B）。
 *
 * 0820 的教训：我当时只验证了「文件存在 / i18n key 存在 / npm build 通过」，
 * 那些是必要条件不是充分条件 —— 真凶（CSP 拦掉 Vite inline 脚本导致整页白屏）
 * 只有真开浏览器才看得到。所以这轮一律用真实浏览器逐页跑。
 *
 * 覆盖四档视口：
 *   - 412×915  一加 15（管理员的测试机，必测）
 *   - 360×640  最窄的现实场景（老机型 / 分屏）
 *   - 390×844  iPhone
 *   - 768×1024 平板（卡片化 ↔ 表格 的切换边界，要确认切换点两边都不难看）
 *
 * 每页断言：
 *   1. 无横向溢出（scrollWidth <= clientWidth + 1）
 *   2. 无控制台 error
 *   3. 无 4xx/5xx 请求
 *   4. 主内容区确有渲染（不是白屏）
 *
 * 用法：node scripts/mobile-audit.mjs [baseURL]
 */
import { chromium } from 'playwright'
import fs from 'node:fs'
import path from 'node:path'

const BASE = process.argv[2] || 'http://127.0.0.1:7799'
const OUT = path.resolve('mobile-audit')

const VIEWPORTS = [
  { name: 'oneplus15', width: 412, height: 915, dpr: 3 },
  { name: 'narrow360', width: 360, height: 640, dpr: 2 },
  { name: 'iphone', width: 390, height: 844, dpr: 3 },
  { name: 'tablet768', width: 768, height: 1024, dpr: 2 }
]

// 与 router/modules 一一对应的菜单页
const PAGES = [
  '/dashboard',
  '/user-manage',
  '/pair-manage',
  '/content-audit',
  '/album-manage',
  '/storage-stats',
  '/app-version',
  '/notify',
  '/system-settings',
  '/audit-log',
  '/network-log',
  '/admin-manage'
]

// 首登改密用的新凭据。口令强度要求：>=12 位且含大小写与数字。
const NEW_ADMIN = 'auditadmin'
const NEW_PASS = 'AuditPass12345'

const CREDS = {
  username: process.env.ADMIN_USER || 'admin',
  password: process.env.ADMIN_PASS || '123456'
}

/**
 * 拖动滑块验证码（若存在）。
 *
 * 登录页用的是"按住滑块拖到最右"式验证。用鼠标事件分多步移动而不是一步到位：
 * 一步跳到终点通常会被判定为非人类操作而失败。
 */
async function dragSliderIfPresent(page) {
  // .dv_handler 是 art-drag-verify 组件的滑块（见 components/core/forms/art-drag-verify）
  const candidates = ['.dv_handler', '.drag_verify .dv_handler_bg']
  for (const sel of candidates) {
    const el = page.locator(sel).first()
    if ((await el.count()) === 0) continue
    const box = await el.boundingBox().catch(() => null)
    if (!box) continue

    // 轨道宽度：取滑块的父容器，拖不到头就不算通过
    const track = await page
      .locator(sel)
      .first()
      .evaluate((node) => {
        const p = node.closest('.drag_verify') || node.parentElement
        return p ? p.getBoundingClientRect().width : 300
      })
      .catch(() => 300)

    const startX = box.x + box.width / 2
    const startY = box.y + box.height / 2
    await page.mouse.move(startX, startY)
    await page.mouse.down()
    // 分 20 步移动，模拟人手
    const distance = track + 40
    for (let i = 1; i <= 20; i++) {
      await page.mouse.move(startX + (distance * i) / 20, startY + (i % 3 === 0 ? 1 : 0))
      await page.waitForTimeout(20)
    }
    await page.mouse.up()
    await page.waitForTimeout(600)
    console.log(`  滑块已拖动（选择器 ${sel}）`)
    return true
  }
  return false
}

/** 用键盘完成一次滑块验证，覆盖无法拖动设备的等价操作。 */
async function completeSliderWithKeyboard(page) {
  const handler = page.locator('.dv_handler').first()
  if ((await handler.count()) === 0) return false
  await handler.focus()
  await page.keyboard.press('Enter')
  await page.waitForTimeout(300)
  const value = await handler.getAttribute('aria-valuenow')
  if (value !== '100') throw new Error(`键盘滑块验证未完成，aria-valuenow=${value}`)
  console.log('  滑块已通过键盘完成')
  return true
}

/**
 * 取当前的哈希路由。
 *
 * 后台是**哈希路由**（`/auth/login#/dashboard`）：base 路径不变，真实路由在 # 后面。
 * 早先用 `url().includes('/auth/login')` 判断"是否还在登录页"，那个条件恒为真，
 * 于是 4 个视口全被误判成"登录失败"——而这个误判还掩盖了真实情况
 *（登录其实是成功的，只是停在首登改密页）。
 */
const hashOf = (page) => new URL(page.url()).hash.replace(/^#/, '') || '/'

async function main() {
  fs.mkdirSync(OUT, { recursive: true })
  // Playwright 自带的 chromium 可能没下载（报 "Executable doesn't exist"），
  // 此时回退系统 Chrome —— 本项目只需要一个真实浏览器来验证渲染，不依赖特定构建。
  const SYSTEM_CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe'
  let browser
  try {
    browser = await chromium.launch({ args: ['--no-sandbox', '--disable-gpu'] })
  } catch {
    console.log('自带 chromium 不可用，回退系统 Chrome')
    browser = await chromium.launch({
      executablePath: SYSTEM_CHROME,
      args: ['--no-sandbox', '--disable-gpu']
    })
  }
  const failures = []
  // 首登改密只会发生一次；之后所有视口都用新凭据。
  let credsChanged = false

  for (const vp of VIEWPORTS) {
    const context = await browser.newContext({
      viewport: { width: vp.width, height: vp.height },
      deviceScaleFactor: vp.dpr,
      isMobile: vp.width < 768,
      hasTouch: true
    })
    const page = await context.newPage()

    const consoleErrors = []
    const badRequests = []
    page.on('console', (m) => {
      if (m.type() === 'error') consoleErrors.push(m.text())
    })
    page.on('response', (r) => {
      if (r.status() >= 400) badRequests.push(`${r.status()} ${r.url()}`)
    })

    // ---- 登录 ----
    await page.goto(`${BASE}/#/auth/login`, { waitUntil: 'networkidle' })
    await page.screenshot({ path: `${OUT}/${vp.name}-login.png`, fullPage: true })

    // 凭据会在第一个视口的首登改密后变更，后续视口必须用新的
    //（否则第 2~4 个视口全部登录失败，又是一轮假失败）。
    const user = credsChanged ? NEW_ADMIN : CREDS.username
    const pass = credsChanged ? NEW_PASS : CREDS.password

    const inputs = page.locator('input')
    if ((await inputs.count()) >= 2) {
      await inputs.nth(0).fill(user)
      await inputs.nth(1).fill(pass)

      // 登录页有**滑块验证码**（"按住滑块拖动"）。不过它就直接点登录，
      // 表单不会提交，于是后续每个路由都被重定向回登录页 —— 而断言
      //（无溢出/无错误/非白屏）在登录页上全都成立，就会得到一个
      // "全部通过"的假绿。这正是 0820「只测登录页会漏掉主界面问题」的重演。
      if (vp.name === 'narrow360') {
        await completeSliderWithKeyboard(page)
      } else {
        await dragSliderIfPresent(page)
      }

      // 顶栏图标已改成语义正确的 <button>，不能再假定页面第一个 button 就是提交按钮。
      await page.locator('button.el-button--primary').first().click()
      await page.waitForTimeout(3000)
    }

    // 必须确认真的离开了登录页，否则后面测的全是登录页
    if (hashOf(page).includes('/auth/login')) {
      failures.push(`${vp.name}: 登录失败，仍在登录页 —— 后续断言无意义`)
      await page.screenshot({ path: `${OUT}/${vp.name}-login-failed.png`, fullPage: true })
      await context.close()
      continue
    }

    // 首登强制改密页：**必须走完**才能到主界面。
    // 0820 的教训：只测登录页会漏掉主界面的问题。
    if (hashOf(page).includes('change-credentials')) {
      console.log(`[${vp.name}] 命中首登改密页，填表通过`)
      await page.screenshot({ path: `${OUT}/${vp.name}-change-cred.png`, fullPage: true })
      const ci = page.locator('input')
      const n = await ci.count()
      // 表单为：新用户名 / 原密码 / 新密码 / 确认新密码 / 邮箱（顺序按页面）
      for (let i = 0; i < n; i++) {
        const type = await ci.nth(i).getAttribute('type')
        const ph = (await ci.nth(i).getAttribute('placeholder')) || ''
        if (type === 'password') {
          await ci.nth(i).fill(ph.includes('原') || ph.includes('当前') ? pass : NEW_PASS)
        } else if (ph.includes('邮箱') || ph.includes('mail')) {
          await ci.nth(i).fill('audit@local.test')
        } else {
          await ci.nth(i).fill(NEW_ADMIN)
        }
      }
      await page.locator('button.el-button--primary').first().click()
      await page.waitForTimeout(3000)
      credsChanged = true
      console.log(`[${vp.name}] 改密后 URL: ${page.url()}`)
    }

    // ---- 逐页 ----
    for (const route of PAGES) {
      consoleErrors.length = 0
      badRequests.length = 0
      await page.goto(`${BASE}/#${route}`, { waitUntil: 'networkidle' }).catch(() => {})
      await page.waitForTimeout(700)

      // 图表等重组件会在进入视口后才初始化。全页截图本身不会触发 IntersectionObserver，
      // 主动滚到最后一个主要内容块再回到顶部，避免把“尚未初始化的空白画布”误判为正常。
      const lazyTarget = page.locator('.chart-card, .el-table, .art-table__card').last()
      if ((await lazyTarget.count()) > 0) {
        await lazyTarget.scrollIntoViewIfNeeded().catch(() => {})
        // 折线图有 1.3s 的入场动画；等它稳定后再截图，否则真实数据会被拍成贴着 0 轴的半成品。
        await page.waitForTimeout(route === '/dashboard' ? 1600 : 300)
        await page.evaluate(() => window.scrollTo(0, 0))
      }

      const metrics = await page.evaluate(() => {
        const de = document.documentElement
        return {
          scrollWidth: de.scrollWidth,
          clientWidth: de.clientWidth,
          bodyText: (document.body.innerText || '').trim().length,
          menuItems: document.querySelectorAll('.el-menu-item').length,
          cards: document.querySelectorAll('.art-table__card').length,
          tables: document.querySelectorAll('.el-table').length,
          unlabeledIconButtons: document.querySelectorAll(
            'button.art-icon-button:not([aria-label])'
          ).length
        }
      })

      const slug = route.replace(/\//g, '_')
      await page.screenshot({ path: `${OUT}/${vp.name}${slug}.png`, fullPage: true })

      const overflow = metrics.scrollWidth > metrics.clientWidth + 1
      const blank = metrics.bodyText < 20
      const actualRoute = hashOf(page).split('?')[0]
      if (actualRoute !== route) {
        failures.push(`${vp.name} ${route}: 最终落在 ${actualRoute}，目标页面未成功打开`)
      }
      if (overflow) {
        failures.push(
          `${vp.name} ${route}: 横向溢出 scrollWidth=${metrics.scrollWidth} > clientWidth=${metrics.clientWidth}`
        )
      }
      if (blank) failures.push(`${vp.name} ${route}: 页面几乎空白（可能白屏）`)
      if (metrics.unlabeledIconButtons > 0) {
        failures.push(
          `${vp.name} ${route}: ${metrics.unlabeledIconButtons} 个图标按钮缺少可访问名称`
        )
      }
      if (consoleErrors.length) {
        failures.push(`${vp.name} ${route}: 控制台错误 ${consoleErrors.slice(0, 2).join(' | ')}`)
      }
      if (badRequests.length) {
        failures.push(`${vp.name} ${route}: 失败请求 ${badRequests.slice(0, 2).join(' | ')}`)
      }

      // 窄屏应走卡片、平板应走表格（卡片化断点 768）
      const mode = vp.width < 768 ? 'card' : 'table'
      console.log(
        `[${vp.name}] ${route} 溢出=${overflow} 卡片=${metrics.cards} 表格=${metrics.tables} 期望=${mode}`
      )
    }

    await context.close()
  }

  await browser.close()

  console.log('\n================ 结果 ================')
  if (failures.length === 0) {
    console.log('全部通过：无横向溢出、无控制台错误、无失败请求、无白屏')
  } else {
    console.log(`发现 ${failures.length} 个问题：`)
    failures.forEach((f) => console.log('  - ' + f))
  }
  console.log(`截图目录：${OUT}`)
  process.exit(failures.length ? 1 : 0)
}

main().catch((e) => {
  console.error('审计脚本异常：', e)
  process.exit(2)
})
