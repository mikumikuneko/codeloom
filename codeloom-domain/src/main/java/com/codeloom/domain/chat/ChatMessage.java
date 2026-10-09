package com.codeloom.domain.chat;

import com.codeloom.domain.project.ProjectId;
import com.codeloom.domain.user.UserId;
import java.time.Instant;
import java.util.Objects;

/**
 * 项目内双方聊天室的一条消息。**纯人类通道**，agent 不参与。
 *
 * <p>{@code id} 由存储层分配（自增主键），因为消息要按全库单调顺序拉取 ——
 * 这一点和 event 的 seq 同理，是"需要全库排序的标识"那一类。
 *
 * @param text           消息正文
 * @param anchorEventSeq 可选锚点：指向某条 agent 事件。A 可以对着 B 的某个工具调用
 *                       或 diff"引用回复"到聊天室，把"我刚才看你那段不对"从模糊指向
 *                       变成精确定位。为 null 表示这是一条普通消息。
 * @param anchorText     锚点那一步**是哪一步**的一句话说明（"编辑了 Foo.java"、"跑了一次 mvn test"），
 *                       发送时抄下来一起存。
 *                       <h2>它不是「把那条事件概括成一句话」</h2>
 *                       它是那一步的**动作**：动词 + 对象 —— 也就是会话流里那一行工具调用
 *                       上本来就显示着的那句话（"读取 OrderService.java"、"运行 mvn -q test"）。
 *                       被引用事件的正文可能很长，而这里要回答的只有一个问题：
 *                       <b>我指的是哪一步</b>。
 *                       <h2>谁产的：**引用方的前端**，不是服务端、也不是 agent</h2>
 *                       服务端从头到尾只是**原样落库、原样推给对方** —— 它不生成这句话，
 *                       也不校验它的内容（连被引用的那条事件都看不到：那属于**对方**那条会话流）。
 *                       agent 更不在这个通道里（聊天室是纯人类通道）。
 *                       所以它**既不是**"服务端从被引用的消息里截一段"，**也不是**"agent 自己写的"，
 *                       而是**引用方按下"引用"那一刻，由界面渲染出来的一句话**。
 *                       <p><b>而它没有生产者。</b>引用那套界面不存在，前端永远发
 *                       {@code null} —— 这个字段、它的列、接口和"同生共死"的校验都还在，
 *                       只是没人填它。所以上面那段描述的是**设计意图**，不是现状。
 *                       <h2>为什么抄一份，而不是每次去查那条事件</h2>
 *                       因为**那条事件属于对方的 agent 流**，聊天室这一侧看不到它；
 *                       而且事件流会随着会话被清理而变样甚至消失 —— 引用该留住的是
 *                       "当时指的是什么"，那是一条已经说过的话的一部分，不是一个能随时查的指针。
 */
public record ChatMessage(ChatMessageId id,
                          ProjectId projectId,
                          UserId authorId,
                          String text,
                          Long anchorEventSeq,
                          String anchorText,
                          Instant createdAt) {

    public ChatMessage {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(authorId, "authorId");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(createdAt, "createdAt");

        if (text.isBlank()) {
            throw new IllegalArgumentException("聊天消息不能为空");
        }
        if (anchorEventSeq != null && anchorEventSeq <= 0) {
            throw new IllegalArgumentException("anchorEventSeq 必须是正数或 null，收到 " + anchorEventSeq);
        }
        // 摘要和锚点同生共死：有锚点没摘要，界面上就是一句"引用了第 42 条"；
        // 反过来有摘要没锚点，那是一个指向空气的引用。两者要么都在要么都不在
        if ((anchorEventSeq == null) != (anchorText == null)) {
            throw new IllegalArgumentException(
                    "锚点和它的摘要必须同时有或同时没有：seq=" + anchorEventSeq + " text=" + anchorText);
        }
    }
}
