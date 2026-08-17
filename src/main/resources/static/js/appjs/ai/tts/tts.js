$(function () {
    var timer = null;
    $('#text').on('input', function () { $('#count').text(this.value.length); });
    $('#ttsForm').on('submit', function (event) {
        event.preventDefault();
        var form = this;
        if (!form.voiceFile.files.length) { layer.msg('请选择参考音频'); return; }
        $('#submitBtn').prop('disabled', true).html('<i class="fa fa-spinner fa-spin"></i> 正在上传');
        $.ajax({
            url: '/ai/tts/generate', type: 'POST', data: new FormData(form),
            processData: false, contentType: false,
            success: function (res) {
                if (res.code !== 0) { fail(res.msg || '创建任务失败'); return; }
                $('#progressPanel').show();
                poll(res.job.id);
            },
            error: function () { fail('上传或创建任务失败'); }
        });
    });
    function poll(id) {
        clearTimeout(timer);
        $.get('/ai/tts/status/' + id, function (res) {
            if (res.code !== 0) { fail(res.msg); return; }
            var job = res.job;
            $('#statusText').text(job.message);
            $('#progressBar').css('width', job.progress + '%').text(job.progress + '%');
            $('#chunkText').text('已完成 ' + job.completedChunks + ' / ' + job.totalChunks + ' 段');
            if (job.status === 'SUCCESS') {
                $('#progressPanel .progress').removeClass('active');
                $('#downloadBtn').attr('href', job.downloadUrl).show();
                $('#submitBtn').prop('disabled', false).html('<i class="fa fa-play"></i> 开始生成');
            } else if (job.status === 'FAILED') { fail(job.message); }
            else { timer = setTimeout(function () { poll(id); }, 2000); }
        }).fail(function () { timer = setTimeout(function () { poll(id); }, 5000); });
    }
    function fail(message) {
        clearTimeout(timer); layer.alert(message || '操作失败');
        $('#submitBtn').prop('disabled', false).html('<i class="fa fa-play"></i> 开始生成');
    }
});
