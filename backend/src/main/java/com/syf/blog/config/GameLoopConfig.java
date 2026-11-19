package com.syf.blog.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 启动单线程调度；测试可不导入此配置，以便手动推进固定帧。 */
@Configuration
@EnableScheduling
public class GameLoopConfig {
}
