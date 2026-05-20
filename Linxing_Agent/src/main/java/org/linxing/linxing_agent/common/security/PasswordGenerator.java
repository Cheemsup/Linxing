package org.linxing.linxing_agent.common.security;

import java.util.Scanner;

@Deprecated
public class PasswordGenerator {

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        
        System.out.println("========================================");
        System.out.println("  Personal Note RAG - 密码生成工具");
        System.out.println("========================================\n");
        
        while (true) {
            System.out.print("请输入要加密的密码 (输入 'quit' 退出): ");
            String input = scanner.nextLine();
            
            if ("quit".equalsIgnoreCase(input.trim())) {
                break;
            }
            
            if (input.trim().isEmpty()) {
                System.out.println("⚠️ 密码不能为空！\n");
                continue;
            }
            
            String encodedPassword = PasswordEncoder.encode(input);
            
            System.out.println("\n✅ 加密成功！");
            System.out.println("原始密码: " + input);
            System.out.println("BCrypt Hash:");
            System.out.println(encodedPassword);
            System.out.println("\n请将上面的Hash值复制到数据库的password_hash字段\n");
        }
        
        scanner.close();
        System.out.println("感谢使用！再见！");
    }
}
