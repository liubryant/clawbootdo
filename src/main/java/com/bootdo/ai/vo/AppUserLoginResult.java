package com.bootdo.ai.vo;

public class AppUserLoginResult {
    private String phone;
    private boolean newUser;

    public AppUserLoginResult(String phone, boolean newUser) {
        this.phone = phone;
        this.newUser = newUser;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public boolean isNewUser() {
        return newUser;
    }

    public void setNewUser(boolean newUser) {
        this.newUser = newUser;
    }
}
