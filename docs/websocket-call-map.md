# WebSocket 方法调用关系图

从浏览器开始，沿箭头看事件如何进入后端。虚线表示 Spring 根据事件自动调用方法，实线表示代码执行、配置关系或消息传递。

```mermaid
flowchart TB
    APP["Spring 启动"] -. "自动调用" .-> CONFIG["MyWsConfig.registerWebSocketHandlers()<br/>注册 /myWs 的处理器和握手拦截器"]
    CONFIG -- "提供路由配置" --> FRAMEWORK["Spring / Tomcat<br/>接收连接、分发事件"]
    BROWSER["浏览器操作<br/>new WebSocket()：申请连接<br/>socket.send()：发送文本<br/>socket.close()：关闭连接"] -- "网络请求或消息" --> FRAMEWORK

    FRAMEWORK -. "握手阶段" .-> HANDSHAKE["MyWsInterceptor<br/>beforeHandshake()：返回 true，允许继续<br/>afterHandshake()：记录握手阶段结束"]
    FRAMEWORK -. "连接建立成功" .-> ESTABLISHED["MyWsHandler.afterConnectionEstablished(session)<br/>包装连接 → 创建 SessionBean → Map.put()<br/>调用 getConnectionCount() 输出日志"]
    FRAMEWORK -. "收到客户端文本" .-> MESSAGE["MyWsHandler.handleTextMessage(session, message)<br/>根据 session.getId() 从 Map 取出 client"]
    FRAMEWORK -. "连接关闭或传输异常" .-> LIFECYCLE["MyWsHandler<br/>afterConnectionClosed()：移除记录，统计日志<br/>handleTransportError()：移除记录，调用 closeSession()"]

    TIMER["Spring 定时调度<br/>@Scheduled，默认间隔约两秒"] -. "自动调用" .-> TASK["MyWsPushTask.sendMsg()<br/>生成服务端时间文本"]
    TASK --> BROADCAST["MyWsHandler.broadcast(message)<br/>遍历 Map 中的每个 client"]

    ESTABLISHED -- "welcome 欢迎消息" --> SEND["MyWsHandler.sendMessage(client, message)<br/>取出连接 → 检查 isOpen() → 统一发送"]
    MESSAGE -- "echo 回复当前连接" --> SEND
    BROADCAST -- "push 推送每个连接" --> SEND

    SEND -- "连接打开，发送文本" --> WRITE["session.sendMessage(new TextMessage(message))<br/>通过保存的并发发送包装对象发送"]
    WRITE -- "网络消息" --> RECEIVE["浏览器 socket.onmessage(event)<br/>读取 event.data，显示文本"]
    SEND -- "连接已关或发送失败" --> CLEANUP["连接已关：Map.remove() 后 return<br/>发送失败：移除记录，再调用 closeSession()"]
    CLEANUP -- "发送失败时" --> CLOSE["MyWsHandler.closeSession(session)<br/>session.close(SERVER_ERROR)，关闭码 1011"]
    LIFECYCLE -- "传输异常时" --> CLOSE

    classDef browser fill:#fef3c7,stroke:#d97706,color:#422006;
    classDef framework fill:#ede9fe,stroke:#7c3aed,color:#2e1065;
    classDef handler fill:#dbeafe,stroke:#2563eb,color:#172554;
    classDef sending fill:#dcfce7,stroke:#16a34a,color:#14532d,stroke-width:2px;
    classDef cleanup fill:#fee2e2,stroke:#dc2626,color:#450a0a;
    class BROWSER,RECEIVE browser;
    class APP,CONFIG,FRAMEWORK,TIMER framework;
    class HANDSHAKE,ESTABLISHED,MESSAGE,TASK,BROADCAST handler;
    class SEND,WRITE sending;
    class LIFECYCLE,CLEANUP,CLOSE cleanup;
```

三个发送入口都汇合到绿色的 `sendMessage(client, message)`：欢迎消息、文本回复、定时推送共用这个方法，再由连接对象的 `sendMessage(TextMessage)` 发送出去。

`afterHandshake()` 表示握手处理阶段结束；连接关闭由 `afterConnectionClosed()` 处理。图中按事件画出回调，业务代码无需依赖 `afterHandshake()` 与连接建立回调之间固定的先后顺序。

`getConnectionCount()` 内部调用 `sessionBeanMap.size()`，给连接建立和关闭时的日志提供数量。

源码：[配置](../backend/src/main/java/com/syf/blog/test/MyWsConfig.java)、[握手拦截器](../backend/src/main/java/com/syf/blog/test/MyWsInterceptor.java)、[消息处理器](../backend/src/main/java/com/syf/blog/test/MyWsHandler.java)、[定时推送](../backend/src/main/java/com/syf/blog/test/MyWsPushTask.java)、[连接记录](../backend/src/main/java/com/syf/blog/test/SessionBean.java)、[浏览器页面](../backend/src/main/resources/static/ws-client.html)。
