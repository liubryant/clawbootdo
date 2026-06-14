$().ready(function () {
    validateRule();
});

$.validator.setDefaults({
    submitHandler: function () {
        save();
    }
});

function save() {
    $.ajax({
        type: "POST",
        url: "/ai/appuser/save",
        data: $('#signupForm').serialize(),
        success: function (r) {
            if (r.code == 0) {
                parent.layer.msg(r.msg);
                parent.reLoad();
                var index = parent.layer.getFrameIndex(window.name);
                parent.layer.close(index);
            } else {
                parent.layer.alert(r.msg);
            }
        }
    });
}

function validateRule() {
    var icon = "<i class='fa fa-times-circle'></i> ";
    $("#signupForm").validate({
        rules: {
            phone: {
                required: true,
                digits: true,
                rangelength: [11, 11]
            },
            password: {
                required: true,
                minlength: 6
            },
            confirm_password: {
                required: true,
                minlength: 6,
                equalTo: "#password"
            }
        },
        messages: {
            phone: {
                required: icon + "请输入手机号",
                digits: icon + "手机号格式不正确",
                rangelength: icon + "手机号格式不正确"
            },
            password: {
                required: icon + "请输入密码",
                minlength: icon + "密码必须6个字符以上"
            },
            confirm_password: {
                required: icon + "请再次输入密码",
                minlength: icon + "密码必须6个字符以上",
                equalTo: icon + "两次输入的密码不一致"
            }
        }
    });
}
