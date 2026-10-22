package com.syf.blog.service;

import com.syf.blog.domain.GameMap;
import com.syf.blog.dto.game.GameMapResponse;
import com.syf.blog.exception.BusinessException;
import com.syf.blog.repository.GameMapRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.stereotype.Service;

// 注册为 Spring 服务，供 Controller 调用
@Service
// 当前服务只查询数据，使用只读事务
@Transactional(readOnly = true)
public class GameMapService {

    private final GameMapRepository gameMapRepository;

    // 通过构造方法注入数据访问接口
    public GameMapService(GameMapRepository gameMapRepository){
        this.gameMapRepository = gameMapRepository;
    }

    /**
     * 根据地图 ID 查询地图。
     *
     * @param id 地图主键
     * @return 包含名称、格子尺寸和地图种子的响应对象
     * @throws BusinessException 地图不存在时返回 404
     */
    public GameMapResponse getById(Long id){
        // 查询地图；没有对应记录时抛出业务异常
        GameMap map = gameMapRepository.findById(id)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND,
                        "地图不存在"
                ));
        return GameMapResponse.from(map);
    }
}
