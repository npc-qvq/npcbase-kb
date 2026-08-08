package cloud.npcbase.kb.access;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提供访问状态查询、唯一密钥解锁和重新锁定接口。
 *
 * @author NPC
 * @date 2026-08-06 16:01:37
 */
@RestController
@RequestMapping("/api/access")
public class AccessController {

    /**
     * 唯一密钥和公开体验权限服务。
     */
    private final AccessService accessService;

    /**
     * 创建访问权限接口控制器。
     *
     * @param accessService 唯一密钥和公开体验权限服务
     */
    public AccessController(AccessService accessService) {
        this.accessService = accessService;
    }

    /**
     * 查询当前浏览器的解锁状态和公开体验剩余次数。
     *
     * @param request 当前 HTTP 请求
     * @param response 当前 HTTP 响应
     * @return 当前访问状态
     */
    @GetMapping("/status")
    public AccessStatusResponse status(HttpServletRequest request, HttpServletResponse response) {
        return accessService.status(request, response);
    }

    /**
     * 校验唯一密钥并为当前浏览器签发解锁凭证。
     *
     * @param request 当前 HTTP 请求
     * @param response 当前 HTTP 响应
     * @param unlockRequest 用户提交的唯一密钥
     * @return 解锁后的访问状态
     */
    @PostMapping("/unlock")
    public AccessStatusResponse unlock(HttpServletRequest request,
                                       HttpServletResponse response,
                                       @Valid @RequestBody AccessUnlockRequest unlockRequest) {
        // 校验密钥并通过 HttpOnly Cookie 返回限时解锁凭证。
        return accessService.unlock(request, response, unlockRequest);
    }

    /**
     * 删除当前浏览器的解锁凭证。
     *
     * @param response 当前 HTTP 响应
     */
    @DeleteMapping("/unlock")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void lock(HttpServletResponse response) {
        accessService.lock(response);
    }
}
