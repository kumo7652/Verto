package com.example;

public class UserServiceImpl implements UserService{
    @Override
    public User getUser(User user){
        System.out.println("用户名：" + user.getName());
        return user;
    }
}
