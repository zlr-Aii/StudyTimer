package com.studytimer;

import java.util.List;

public class PolarBellTest {

    public static void main(String[] args) {

        try (PolarBellClient client = new PolarBellClient()) {

            // 1. 连接手机
            client.connect();

            // 2. 测试通信
            boolean connected = client.ping();
            System.out.println("通信状态：" + connected);

            if (!connected) {
                return;
            }

            // 3. 获取手机音频列表
            List<PhoneAudio> audios = client.getAudioList();

            System.out.println("\n手机音频列表：");

            for (int i = 0; i < audios.size(); i++) {
                PhoneAudio audio = audios.get(i);

                System.out.println(
                        (i + 1) + ". " + audio.getName()
                );

                System.out.println(
                        "   ID: " + audio.getAudioId()
                );

                System.out.println(
                        "   时长: " + audio.getDuration() / 1000 + " 秒"
                );
            }

            // 4. 播放第一首音频
            if (!audios.isEmpty()) {

                PhoneAudio firstAudio = audios.get(0);

                System.out.println(
                        "\n正在请求手机播放：" + firstAudio.getName()
                );

                client.playAudio(firstAudio.getAudioId());

                System.out.println("播放请求已发送");
            } else {
                System.out.println("\n手机媒体库为空");
            }

        } catch (Exception e) {
            System.err.println("PolarBell 测试失败：");
            e.printStackTrace();
        }
    }
}