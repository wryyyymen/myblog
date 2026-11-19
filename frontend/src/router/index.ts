import { createRouter, createWebHistory } from 'vue-router'
import HomeView from '../views/HomeView.vue'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  // 切换页面时回到顶部，浏览器后退时恢复原来的阅读位置。
  scrollBehavior(to, _from, savedPosition) {
    if (savedPosition) return savedPosition
    if (to.hash) return { el: to.hash }
    return { top: 0 }
  },
  routes: [
    {
      path: '/',
      name: 'home',
      component: HomeView,
    },
    {
      path: '/articles/:id/edit',
      name: 'article-edit',
      component: () => import('../views/ArticleEditView.vue'),
    },
    {
      path: '/articles/new',
      name: 'article-create',
      component: () => import('../views/ArticleCreateView.vue'),
    },
    {
      path: '/articles/:id',
      name: 'article-detail',
      component: () => import('../views/ArticleDetailView.vue'),
    },
    {
      path: '/about',
      name: 'about',
      // route level code-splitting
      // this generates a separate chunk (About.[hash].js) for this route
      // which is lazy-loaded when the route is visited.
      component: () => import('../views/AboutView.vue'),
    },
    {
      path: '/login',
      name: 'login',
      component: () => import('../views/LoginView.vue'),
    },
    {
      path: '/register',
      name: 'register',
      component: () => import('../views/RegisterView.vue'),
    },
    {
      path: '/game',
      name: 'game',
      component: () => import('../views/GameMenuView.vue'),
    },
    {
      path: '/game/single',
      name: 'game-single',
      component: () => import('../views/SinglePlayerGameView.vue'),
      children: [
        {
          path: 'result',
          name: 'game-single-result',
          component: () => import('../views/GameResultView.vue'),
        },
      ],
    },
    {
      path: '/game/online',
      name: 'game-online',
      component: () => import('../views/GameView.vue'),
      children: [
        {
          path: 'result/:matchId',
          name: 'game-online-result',
          component: () => import('../views/GameResultView.vue'),
        },
      ],
    },
  ],
})

// Vite 在生产构建时移除此分支，学习页面也不会生成发布用的页面文件。
if (import.meta.env.DEV) {
  router.addRoute({
    path: '/game/ws-test',
    name: 'websocket-test',
    component: () => import('../views/WebSocketTestView.vue'),
  })
}

export default router
