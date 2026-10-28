import { createRouter, createWebHistory } from 'vue-router'
import NewTask from './views/NewTask.vue'
import TaskList from './views/TaskList.vue'
import TaskDetail from './views/TaskDetail.vue'
import SheetWorkbench from './views/SheetWorkbench.vue'
import SheetRepo from './views/SheetRepo.vue'
import OpsView from './views/OpsView.vue'
import NotFound from './views/NotFound.vue'

const routes = [
  { path: '/', name: 'new-task', component: NewTask, meta: { title: '新建任务' } },
  { path: '/tasks/new', redirect: '/' },
  { path: '/tasks', name: 'tasks', component: TaskList, meta: { title: '任务记录' } },
  { path: '/tasks/:id', name: 'task-detail', component: TaskDetail, meta: { title: '任务详情' } },
  { path: '/sheets', name: 'sheets', component: SheetWorkbench, meta: { title: '表格智作' } },
  { path: '/sheets/repo', name: 'sheet-repo', component: SheetRepo, meta: { title: '我的仓库' } },
  { path: '/ops', name: 'ops', component: OpsView, meta: { title: '运行观测' } },
  { path: '/:pathMatch(.*)*', name: 'not-found', component: NotFound, meta: { title: '页面不存在' } }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.afterEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} · 汇表` : '汇表'
})

export default router
