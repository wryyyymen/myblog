-- 向 myblog 新增 4 个测试分类和 12 篇测试文章。
-- 分类名称唯一；重复运行会报错，应通过 mysql 批处理输入运行，遇到错误中止。
SET NAMES utf8mb4;
START TRANSACTION;

SET @seed_author = (SELECT id FROM users WHERE username = 'potato');
SET @seed_time = CURRENT_TIMESTAMP(3);

INSERT INTO categories (name, description, is_enabled) VALUES
('测试·Vue 学习', '用于练习文章列表、详情页和表单交互的测试分类。', 1),
('测试·后端开发', '用于练习 Spring Boot 接口及文章增删改查的测试分类。', 1),
('测试·数据库实践', '用于练习字段映射、外键和分类查询的测试分类。', 1),
('测试·项目记录', '用于记录博客项目开发过程的测试分类。', 1);

SET @seed_category_count = ROW_COUNT();
SET @seed_vue = (SELECT id FROM categories WHERE name = '测试·Vue 学习');
SET @seed_backend = (SELECT id FROM categories WHERE name = '测试·后端开发');
SET @seed_database = (SELECT id FROM categories WHERE name = '测试·数据库实践');
SET @seed_project = (SELECT id FROM categories WHERE name = '测试·项目记录');

INSERT INTO blogs (author_id, category_id, title, content, view_count, created_at) VALUES
(@seed_author, @seed_vue, '【测试】Vue 的数据如何显示到页面上',
'这是一篇用于测试文章详情页的示例文章。首页请求文章列表后，把返回的数据保存到响应式变量中，模板就能显示文章标题。

练习：修改这篇文章的标题，保存后返回首页，观察列表是否显示新的标题。', 12, @seed_time - INTERVAL 11 MINUTE),
(@seed_author, @seed_vue, '【测试】使用 v-model 收集表单内容',
'发布页面包含标题和正文输入框。通过 v-model，可以让输入框内容与页面中的变量保持同步。

练习：输入两段正文并发布，然后检查详情页中的换行是否正确显示。', 8, @seed_time - INTERVAL 10 MINUTE),
(@seed_author, @seed_vue, '【测试】路由参数与查询参数',
'文章详情页使用路径中的文章 ID 来请求数据。分类筛选则通过 categoryId 查询参数指定要查看的分类。

练习：在首页切换分类，观察浏览器网络面板中请求地址的变化。', 0, @seed_time - INTERVAL 9 MINUTE),
(@seed_author, @seed_backend, '【测试】认识文章查询接口',
'后端接收到文章列表请求后，Controller 调用 Repository 查询数据库，并把查询结果返回给前端。

练习：分别请求文章列表和文章详情，比较 JSON 数组与单个 JSON 对象的区别。', 21, @seed_time - INTERVAL 8 MINUTE),
(@seed_author, @seed_backend, '【测试】发布与编辑文章',
'发布文章使用 POST 请求，编辑已有文章使用 PUT 请求。请求正文包含标题和正文，后端校验数据后保存修改。

练习：修改本篇文章的正文，再刷新详情页，确认修改已经保存到数据库。', 15, @seed_time - INTERVAL 7 MINUTE),
(@seed_author, @seed_backend, '【测试】删除一篇测试文章',
'这篇示例文章可以用于练习删除功能。进入详情页，点击删除按钮并确认，删除成功后应回到首页。

练习：确认首页列表中不再出现这篇文章，然后再次请求原文章详情，检查是否返回 404。', 5, @seed_time - INTERVAL 6 MINUTE),
(@seed_author, @seed_database, '【测试】实体字段与数据库列名',
'Java 实体中可以使用 createdAt 这样的字段名，通过 Column 注解映射到数据库中的 created_at 列。

练习：查看 Article 实体，找出 authorId、categoryId 与数据库列名之间的对应关系。', 9, @seed_time - INTERVAL 5 MINUTE),
(@seed_author, @seed_database, '【测试】外键为什么限制删除',
'评论和收藏记录会通过文章 ID 关联到文章。当前数据库使用外键规则来约束这些关系，避免留下无效引用。

练习：比较一篇带有关联记录的文章与一篇新建测试文章的删除结果。', 7, @seed_time - INTERVAL 4 MINUTE),
(@seed_author, @seed_database, '【测试】按分类查询文章',
'文章表中的 category_id 记录所属分类。后端根据这个字段筛选数据，再按创建时间倒序返回文章列表。

练习：选择数据库实践分类，检查返回文章的 categoryId 是否与所选分类一致。', 0, @seed_time - INTERVAL 3 MINUTE),
(@seed_author, @seed_project, '【测试】博客开发的第一阶段',
'当前博客已具备文章列表、详情、发布、编辑和删除页面。每个页面都通过接口与后端交换数据。

练习：从发布页面创建一篇文章，再依次进入详情页和编辑页，检查整个操作流程。', 18, @seed_time - INTERVAL 2 MINUTE),
(@seed_author, @seed_project, '【测试】整理一次页面加载失败',
'页面加载数据需要经历发起请求、接收响应和更新界面的过程。加载状态和失败提示可以帮助用户理解当前情况。

练习：在浏览器开发者工具中切换到离线模式，观察页面是否显示失败提示，完成后恢复网络。', 3, @seed_time - INTERVAL 1 MINUTE),
(@seed_author, @seed_project, '【测试】下一次开发计划',
'分类筛选完成后，可以继续完善文章列表的分页，以及发布表单中的提示信息。

练习：记录一个你希望改进的功能，先描述用户操作，再列出需要增加的接口或页面。', 0, @seed_time);

SET @seed_article_count = ROW_COUNT();
COMMIT;

SELECT @seed_category_count AS inserted_categories, @seed_article_count AS inserted_articles, @seed_author AS author_id;
SELECT c.id AS category_id, c.name, COUNT(b.id) AS article_count
FROM categories c LEFT JOIN blogs b ON b.category_id = c.id
WHERE c.id IN (@seed_vue, @seed_backend, @seed_database, @seed_project)
GROUP BY c.id, c.name ORDER BY c.id;
SELECT id, title, author_id, category_id, view_count
FROM blogs
WHERE category_id IN (@seed_vue, @seed_backend, @seed_database, @seed_project)
ORDER BY id;
SELECT (SELECT COUNT(*) FROM categories) AS total_categories, (SELECT COUNT(*) FROM blogs) AS total_articles;
