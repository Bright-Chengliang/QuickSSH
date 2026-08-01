package com.quickssh.app.utils

/**
 * ANSI 转义序列过滤工具类（增强版）
 * 用于清理 SSH 终端输出中的所有控制字符，确保 UI 界面完全无乱码
 */
object AnsiUtils {
    
    /**
     * 移除字符串中的所有 ANSI 转义序列和控制字符
     * @param text 原始终端输出文本
     * @return 清理后的纯文本
     */
    fun stripAnsiCodes(text: String): String {
        var cleaned = text
        
        // 1. 移除 OSC 序列 (ESC ] ... BEL 或 ESC ] ... ST)
        // OSC 可能以 BEL (\u0007) 或 ST (\u001B\\) 终止
        cleaned = cleaned.replace(Regex("\u001B\\]([^\u0007\u001B]*?)(\u0007|\u001B\\\\)"), "")
        
        // 2. 移除 CSI 序列 (ESC [ ... 字母)
        // 包括标准 CSI 和 DEC 私有模式 (CSI ? ... h/l)
        cleaned = cleaned.replace(Regex("\u001B\\[[0-9;?]*[a-zA-Z]"), "")
        
        // 3. 移除带有中间字节的 CSI 序列 (ESC [ ... 空格/!/"/#/$ ... 字母)
        cleaned = cleaned.replace(Regex("\u001B\\[[0-9;]*[ !\"#$%&'()*+,\\-./:;<=>?]*[a-zA-Z]"), "")
        
        // 4. 移除其他 ESC 序列 (ESC 后跟单个字符的序列)
        cleaned = cleaned.replace(Regex("\u001B[><=\\(\\)NOMED78HcZB]"), "")
        
        // 5. 移除单独的 ESC 字符
        cleaned = cleaned.replace(Regex("\u001B"), "")
        
        // 6. 移除其他控制字符但保留换行符和制表符
        // 移除所有 ASCII 控制字符 (0x00-0x1F) 除了 \n (0x0A) 和 \t (0x09)
        cleaned = cleaned.replace(Regex("[\u0000-\u0008\u000B-\u001F]"), "")
        
        // 7. 移除单独的回车符（但保留 \r\n）
        cleaned = cleaned.replace(Regex("\r(?!\n)"), "")
        
        return cleaned
    }
    
    /**
     * 批量清理日志列表中的 ANSI 转义序列
     */
    fun stripAnsiCodesFromList(logs: List<String>): List<String> {
        return logs.map { stripAnsiCodes(it) }
    }
}
