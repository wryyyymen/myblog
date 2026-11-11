package com.syf.blog.service;

import com.syf.blog.domain.GameMap;
import com.syf.blog.dto.game.GameMapResponse;
import com.syf.blog.exception.BusinessException;
import com.syf.blog.repository.GameMapRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.stereotype.Service;

/**
 * 地图查询业务服务，在只读事务中访问数据库并返回地图响应对象。
 *
 * <p>地图属于公开读取数据；当前方法不执行登录或房间成员校验。
 */
@Service
// 当前服务只查询数据，使用只读事务
@Transactional(readOnly = true)
public class GameMapService {

    private final GameMapRepository gameMapRepository;

    /**
     * 创建地图查询服务。
     *
     * @param gameMapRepository 按地图主键访问持久化数据的 Repository
     */
    public GameMapService(GameMapRepository gameMapRepository){
        this.gameMapRepository = gameMapRepository;
    }

    /**
     * 根据主键查找地图，并将实体转换成对外响应对象。
     *
     * <p>HTTP 调用方在 Controller 层校验 ID 为正数；本方法负责查询及不存在的业务失败。
     *
     * @param id 要查询的地图主键
     * @return 包含地图 ID、名称、格子尺寸和二维初始布局的响应对象
     * @throws BusinessException 地图不存在时抛出，携带 HTTP 404 状态
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
