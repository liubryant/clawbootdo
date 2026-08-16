var prefix = '/inspireplanet/model-config';

$(function () {
    $('.model-form').each(function () {
        var form = $(this);
        var type = form.data('type');
        $.get(prefix + '/get', { configType: type }, function (config) {
            if (!config) return;
            form.find('[name=aiProvider]').val(config.aiProvider || 'doubao');
            form.find('[name=aiBaseUrl]').val(config.aiBaseUrl || 'https://ark.cn-beijing.volces.com/api/v3');
            form.find('[name=aiApiKey]').val(config.aiApiKey || '');
            form.find('[name=aiModel]').val(config.aiModel || (type === 'VIDEO_TEXT' ? 'doubao-seedance-1-5-pro-251215' : 'doubao-seed-evolving'));
            form.find('.key-hint').text('API Key 已保存在服务端，仅后台管理员可查看和修改。');
        }).fail(function () { layer.msg(type + ' 配置加载失败'); });
    });

    $('.save-button').on('click', function () {
        var form = $(this).closest('.model-form');
        var data = {
            configType: form.data('type'),
            aiProvider: form.find('[name=aiProvider]').val().trim(),
            aiBaseUrl: form.find('[name=aiBaseUrl]').val().trim(),
            aiApiKey: form.find('[name=aiApiKey]').val().trim(),
            aiModel: form.find('[name=aiModel]').val().trim()
        };
        $.post(prefix + '/update', data, function (result) {
            if (result.code === 0) {
                form.find('.key-hint').text('API Key 已保存。');
                layer.msg('保存成功');
            } else layer.msg(result.msg || '保存失败');
        }).fail(function () { layer.msg('请求失败'); });
    });
});
