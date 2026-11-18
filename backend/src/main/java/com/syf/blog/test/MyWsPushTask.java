package com.syf.blog.test;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** 演示服务端主动发送消息，不需要浏览器先发请求。 */
@Component
public class MyWsPushTask {
    private final MyWsHandler myWsHandler;

    /**
     * Spring 创建定时任务对象时，传入它管理的 WebSocket 处理器。
     *
     * @param myWsHandler 保存在线连接并提供广播方法的处理器对象
     */
    public MyWsPushTask(MyWsHandler myWsHandler) {
        this.myWsHandler = myWsHandler;
    }

    /**
     * 由 Spring 定时调用，生成服务端时间文本并广播给当前登记的连接。
     * 应用启用定时任务后，先等待 initialDelay，再执行第一次；
     * 此后每次执行结束，等待 fixedDelay，再执行下一次。
     * ${demo.websocket.push-delay-ms:2000} 表示读取指定配置，未配置时使用 2000 毫秒。
     * 方法没有参数，发送文本在方法内部生成。
     */
    @Scheduled(fixedDelayString = "${demo.websocket.push-delay-ms:2000}",
            initialDelayString = "${demo.websocket.push-delay-ms:2000}")
    public void sendMsg() {
        myWsHandler.broadcast("push: server time=" + Instant.now());
    }
}
