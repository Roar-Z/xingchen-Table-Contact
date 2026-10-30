import { createRouter, createWebHistory } from 'vue-router'
import { project } from './project'
import NewTask from './views/NewTask.vue'
import TaskList from './views/TaskList.vue'
import TaskDetail from './views/TaskDetail.vue'
import SheetWorkbench from './views/SheetWorkbench.vue'
import SheetRepo from './views/SheetRepo.vue'
import OpsView from './views/OpsView.vue'
import DocView from './views/DocView.vue'
import NotFound from './views/NotFound.vue'

const routes = [
  // 标签页标题跟页面自己的标题保持一致：首页正文叫「智能体工作台」，就不要写成「新建任务」
  { path: '/', name: 'new-task', component: NewTask, meta: { title: '智能体工作台' } },
  { path: '/tasks/new', redirect: '/' },
  { path: '/tasks', name: 'tasks', component: TaskList, meta: { title: '任务记录' } },
  { path: '/tasks/:id', name: 'task-detail', component: TaskDetail, meta: { title: '任务详情' } },
  { path: '/sheets', name: 'sheets', component: SheetWorkbench, meta: { title: '表格智作' } },
  { path: '/sheets/repo', name: 'sheet-repo', component: SheetRepo, meta: { title: '我的仓库' } },
  { path: '/ops', name: 'ops', component: OpsView, meta: { title: '运行观测' } },
  // 文档在应用内的右侧内容区打开，meta.doc 交给 DocView 去决定嵌哪一页静态文档
  {
    path: '/docs/product',
    name: 'doc-product',
    component: DocView,
    meta: { title: '产品设计文档', doc: { title: '产品设计文档', src: '/docs/product.html', word: '/docs/product.docx', icon: 'book' } }
  },
  {
    path: '/docs/dev',
    name: 'doc-dev',
    component: DocView,
    meta: { title: '开发文档', doc: { title: '开发文档', src: '/docs/dev.html', word: '/docs/dev.docx', icon: 'code' } }
  },
  { path: '/:pathMatch(.*)*', name: 'not-found', component: NotFound, meta: { title: '页面不存在' } }
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  // 切页面时把滚动条复位到顶部；浏览器前进 / 后退才回到原来的位置
  scrollBehavior(to, from, savedPosition) {
    return savedPosition || { top: 0 }
  }
})

router.afterEach((to) => {
  // 标签页统一带品牌名，和侧边栏「星尘汇表智联」一致，不再用旧的「汇表」
  document.title = to.meta.title ? `${to.meta.title} · ${project.team}` : project.team
})

export default router
