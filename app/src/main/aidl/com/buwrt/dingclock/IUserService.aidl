package com.buwrt.dingclock;

interface IUserService {
    // 16777114 是 Shizuku 服务端保留的 destroy 事务号，不能改
    void destroy() = 16777114;

    void exit() = 1;

    /**
     * 执行 shell 命令，返回 "EXIT:<code>:<plain|gzip>\n<payload>"。
     * gzip 时 payload 为 Base64 后的压缩文本，避免超过 Binder 事务大小限制。
     */
    String exec(String command, long timeoutMs) = 2;
}
