package com.example;

import com.example.proxy.ServiceProxyFactory;

public class EasyConsumerExample {
    public static void main(String[] args) {
        UserService userService = ServiceProxyFactory.getProxy(UserService.class);

        User user = new User();
        user.setName("zhangsan");

        for (int i = 0; i < 50; i++) {
            // 调用
            User newUser = userService.getUser(user);

            if (newUser != null) {
                System.out.println(newUser.getName());
            } else {
                System.out.println("user == null");
            }
        }

    }
}
