package com.syf.blog.auth;

import com.syf.blog.domain.BlogUser;
import com.syf.blog.exception.BusinessException;
import com.syf.blog.repository.BlogUserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 统一管理基于 {@link HttpSession} 的登录身份，包括建立会话、读取身份和退出登录。
 *
 * <p>登录时，将服务端确认的用户 ID 以 {@link Long} 类型保存到 Session；
 * 读取身份时，再查询数据库，确认用户仍然存在且账号已启用。
 * 返回的用户信息及角色来自数据库。
 *
 * <p>本类可供 HTTP 接口和 WebSocket 握手阶段复用。
 * 文章、房间等资源的操作权限由对应业务 Service 检查；
 * 已有 WebSocket 连接的注销、过期联动需要由连接管理逻辑另外处理。
 */
@Service
public class CurrentUserService {
    // Session 中保存登录用户 ID 的属性名，属性值约定为 Long。
    private static final String LOGIN_USER_ID = "loginUserId";

    // 普通业务接口使用的统一登录失效提示。
    private static final String LOGIN_REQUIRED = "请先登录或登录状态已失效";

    private final BlogUserRepository users;

    /**
     * 创建登录身份服务，由 Spring 通过构造方法注入用户数据访问组件。
     *
     * @param users 用于查询用户是否存在、是否启用以及当前角色的 Repository
     */
    public CurrentUserService(BlogUserRepository users) {
        this.users = users;
    }

    /**
     * 要求当前请求具有有效登录身份，并返回当前用户。
     *
     * <p>供文章、分类等受保护接口以及游戏 WebSocket 握手调用。
     * 会话身份检查通过后，再查询数据库中的账号状态。
     * 校验失败时使用统一的登录失效提示。
     *
     * @param request 当前 HTTP 请求，用于取得已有会话；校验过程不会创建新会话
     * @return 数据库中仍然存在且已启用的登录用户，角色以数据库记录为准
     * @throws BusinessException 无有效登录会话、用户已删除或账号已停用时抛出，携带 HTTP 401 状态
     */
    public BlogUser requireLogin(HttpServletRequest request) {
        return requireUser(request, false);
    }

    /**
     * 为“查询当前用户”接口读取有效登录身份，并提供具体的失效原因。
     *
     * <p>校验规则与 {@link #requireLogin(HttpServletRequest)} 相同，
     * 错误提示分别为“请先登录”“登录用户不存在，请重新登录”或“账号已停用”。
     *
     * @param request 当前 HTTP 请求，用于取得已有会话；校验过程不会创建新会话
     * @return 数据库中仍然存在且已启用的登录用户
     * @throws BusinessException 未登录、登录用户已删除或账号已停用时抛出，携带 HTTP 401 状态
     */
    public BlogUser requireProfileUser(HttpServletRequest request) {
        return requireUser(request, true);
    }

    /**
     * 在调用方完成用户名、密码及账号状态校验后，建立登录会话。
     *
     * <p>取得现有会话，不存在时创建会话；随后更换会话 ID，
     * 以降低会话固定攻击的风险，并将可信用户 ID 保存到会话属性中。
     *
     * @param request 登录成功时的 HTTP 请求，用于获取或创建会话并更换会话 ID
     * @param userId 调用方校验成功后取得的非空用户 ID，应来自服务端数据库中的用户记录
     */
    public void startSession(HttpServletRequest request, Long userId) {
        // 无会话时创建会话，已有会话时复用。
        HttpSession session = request.getSession();
        // 更换会话 ID，取得的 session 引用仍可用于保存属性。
        request.changeSessionId();
        // 后续请求通过这个属性读取登录身份。
        session.setAttribute(LOGIN_USER_ID, userId);
    }

    /**
     * 注销当前请求对应的服务端会话，使该会话及其全部属性失效。
     *
     * <p>没有会话时直接结束，因此匿名调用不会创建新会话。
     * 已建立的 WebSocket 连接需要另外联动失效处理。
     *
     * @param request 退出登录时的 HTTP 请求，用于定位需要注销的会话
     */
    public void logout(HttpServletRequest request) {
        // false 表示只查找已有会话，不创建新会话。
        HttpSession session = request.getSession(false);
        if (session != null) {
            // 注销整个会话，包括其中保存的 loginUserId。
            session.invalidate();
        }
    }

    /**
     * 执行公共登录校验：读取会话身份，再检查数据库中的用户状态。
     *
     * <p>用户不存在或已停用时，同时注销失效会话。
     * 缺少会话或登录属性时，直接抛出未登录异常。
     *
     * @param request 当前 HTTP 请求，只读取已有 Session
     * @param detailedMessage 是否提供具体失效原因；true 使用具体提示，false 使用统一提示，校验规则相同
     * @return 数据库中仍然存在且已启用的登录用户
     * @throws BusinessException 无有效会话、登录属性无效、用户已删除或账号已停用时抛出，携带 HTTP 401 状态
     */
    private BlogUser requireUser(HttpServletRequest request, boolean detailedMessage) {
        // 1. 读取已有会话；匿名请求不会因身份校验而获得新 Session。
        HttpSession session = request.getSession(false);
        // 2. 登录属性必须为 Long；通过类型检查后取得 userId。
        if (session == null
                || !(session.getAttribute(LOGIN_USER_ID) instanceof Long userId)) {
            throw unauthorized(detailedMessage ? "请先登录" : LOGIN_REQUIRED);
        }

        // 3. 按可信用户 ID 查询数据库，确认账号当前是否有效。
        BlogUser user = users.findById(userId).orElse(null);
        if (user == null) {
            // 用户已删除，原登录会话一并失效。
            session.invalidate();
            throw unauthorized(detailedMessage ? "登录用户不存在，请重新登录" : LOGIN_REQUIRED);
        }
        // 4. 只有明确为 true 才表示启用，false 和 null 均拒绝登录。
        if (!Boolean.TRUE.equals(user.getEnabled())) {
            session.invalidate();
            throw unauthorized(detailedMessage ? "账号已停用" : LOGIN_REQUIRED);
        }
        // 5. 返回数据库中的用户对象，供调用方继续检查具体业务权限。
        return user;
    }

    /**
     * 构造携带 HTTP 401 状态的业务异常，由调用方使用 throw 抛出。
     *
     * @param message 可以向客户端展示的登录失效提示
     * @return 新建的未登录业务异常对象
     */
    private BusinessException unauthorized(String message) {
        return new BusinessException(HttpStatus.UNAUTHORIZED, message);
    }
}
