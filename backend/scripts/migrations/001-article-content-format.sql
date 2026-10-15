-- 在已存在 blogs 表的 myblog 数据库中执行。
-- 只增加格式列；历史文章自动标记为 TEXT，不转换或覆盖旧正文。
-- 重复执行时通过 information_schema 检查，避免重复添加。
SET @content_format_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'blogs' AND COLUMN_NAME = 'content_format'
);
SET @content_format_sql = IF(
    @content_format_exists = 0,
    'ALTER TABLE blogs ADD COLUMN content_format VARCHAR(16) NOT NULL DEFAULT ''TEXT'' AFTER content',
    'SELECT ''content_format already exists'' AS migration_status'
);
PREPARE content_format_statement FROM @content_format_sql;
EXECUTE content_format_statement;
DEALLOCATE PREPARE content_format_statement;
