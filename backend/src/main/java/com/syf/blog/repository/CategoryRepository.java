package com.syf.blog.repository;

import com.syf.blog.domain.Category;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
/*
*        部分	                含义
    List<Category>	返回一个存放 Category 对象的 List 集合
    findBy	        根据条件查询
    EnabledTrue	    查询 enabled = true 的数据
    OrderBy	        按某个字段排序
    NameAsc	        根据 name 字段升序排列
    * */
public interface CategoryRepository extends JpaRepository<Category, Long> {
    List<Category> findByEnabledTrueOrderByNameAsc();

    /**
     * 查询全部分类，包含启用和停用的分类，按名称升序排列。
     *
     * @return 分类列表
     */
    List<Category> findAllByOrderByNameAsc();


}
