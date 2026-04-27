-- =====================================================
-- Personal Note RAG - 用户认证测试数据
-- 数据库: PostgreSQL 14+
-- 创建时间: 2026-04-26
-- 用途: 用于测试登录功能
-- =====================================================

-- 清理现有测试数据（可选，谨慎执行）
-- DELETE FROM users WHERE username IN ('admin', 'testuser', 'demo');

-- 插入管理员账户
-- 用户名: admin
-- 密码: admin123 (BCrypt加密)
INSERT INTO users (username, password_hash, created_at) 
VALUES (
    'admin',
    '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi',
    NOW()
) ON CONFLICT (username) DO NOTHING;

-- 插入普通测试用户
-- 用户名: testuser
-- 密码: test123456 (BCrypt加密)
INSERT INTO users (username, password_hash, created_at) 
VALUES (
    'testuser',
    '$2a$10$EixZaYVK1fsbw1ZfbX3OXePaWxn96p36WQoeG6Lruj3vjPGga31lW',
    NOW()
) ON CONFLICT (username) DO NOTHING;

-- 插入演示用户
-- 用户名: demo
-- 密码: demo123456 (BCrypt加密)
INSERT INTO users (username, password_hash, created_at) 
VALUES (
    'demo',
    '$2a$10$PvTqfLk0hN/H/KKHMbF2FuH0jFlS5wvDBz9XZGtV2X3Y7R8mN4pQs',
    NOW()
) ON CONFLICT (username) DO NOTHING;

-- 验证插入结果
SELECT id, username, LEFT(password_hash, 20) as password_prefix, created_at 
FROM users 
WHERE username IN ('admin', 'testuser', 'demo')
ORDER BY id;

-- =====================================================
-- 使用说明:
-- 1. 确保PostgreSQL服务已启动
-- 2. 连接到vectordb数据库
-- 3. 执行此SQL脚本
-- 4. 使用以下账号登录测试:
--    - admin / admin123
--    - testuser / test123456  
--    - demo / demo123456
-- =====================================================
