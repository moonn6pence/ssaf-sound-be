package com.ssafy.ssafsound.domain.comment.service;

import com.ssafy.ssafsound.domain.auth.dto.AuthenticatedMember;
import com.ssafy.ssafsound.domain.comment.domain.Comment;
import com.ssafy.ssafsound.domain.comment.domain.CommentLike;
import com.ssafy.ssafsound.domain.comment.domain.CommentNumber;
import com.ssafy.ssafsound.domain.comment.dto.CommentIdElement;
import com.ssafy.ssafsound.domain.comment.dto.GetCommentResDto;
import com.ssafy.ssafsound.domain.comment.dto.PatchCommentUpdateReqDto;
import com.ssafy.ssafsound.domain.comment.dto.PostCommentWriteReqDto;
import com.ssafy.ssafsound.domain.comment.exception.CommentErrorInfo;
import com.ssafy.ssafsound.domain.comment.exception.CommentException;
import com.ssafy.ssafsound.domain.comment.repository.CommentLikeRepository;
import com.ssafy.ssafsound.domain.comment.repository.CommentNumberRepository;
import com.ssafy.ssafsound.domain.comment.repository.CommentRepository;
import com.ssafy.ssafsound.domain.member.domain.Member;
import com.ssafy.ssafsound.domain.member.exception.MemberErrorInfo;
import com.ssafy.ssafsound.domain.member.exception.MemberException;
import com.ssafy.ssafsound.domain.member.repository.MemberRepository;
import com.ssafy.ssafsound.domain.notification.domain.NotificationType;
import com.ssafy.ssafsound.domain.notification.domain.ServiceType;
import com.ssafy.ssafsound.domain.notification.event.NotificationEvent;
import com.ssafy.ssafsound.domain.notification.message.NotificationMessage;
import com.ssafy.ssafsound.domain.post.domain.Post;
import com.ssafy.ssafsound.domain.post.dto.PostCommonLikeResDto;
import com.ssafy.ssafsound.domain.post.exception.PostErrorInfo;
import com.ssafy.ssafsound.domain.post.exception.PostException;
import com.ssafy.ssafsound.domain.post.repository.PostRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@Slf4j
@RequiredArgsConstructor
public class CommentService {
    private final CommentRepository commentRepository;
    private final CommentNumberRepository commentNumberRepository;
    private final CommentLikeRepository commentLikeRepository;
    private final PostRepository postRepository;
    private final MemberRepository memberRepository;
    private final ApplicationEventPublisher applicationEventPublisher;

    @Transactional
    public CommentIdElement writeComment(Long postId, Long loginMemberId, PostCommentWriteReqDto postCommentWriteReqDto) {
        Post post = postRepository.findByIdWithMember(postId)
                .orElseThrow(() -> new PostException(PostErrorInfo.NOT_FOUND_POST));

        Member loginMember = memberRepository.findById(loginMemberId)
                .orElseThrow(() -> new MemberException(MemberErrorInfo.MEMBER_NOT_FOUND_BY_ID));

        CommentNumber commentNumber = commentNumberRepository.
                findByPostIdAndMemberId(postId, loginMemberId).orElse(null);

        if (commentNumber == null) {
            commentNumber = CommentNumber.builder()
                    .post(postRepository.getReferenceById(postId))
                    .member(loginMember)
                    .number(commentNumberRepository.countAllByPostId(postId) + 1)
                    .build();
            commentNumberRepository.save(commentNumber);
        }

        Comment comment = Comment.builder()
                .post(post)
                .member(loginMember)
                .content(postCommentWriteReqDto.getContent())
                .anonymity(postCommentWriteReqDto.getAnonymity())
                .commentNumber(commentNumber)
                .commentGroup(null)
                .build();

        comment = commentRepository.save(comment);
        commentRepository.updateByCommentGroup(comment.getId());

        if (!post.isMine(loginMember)) {
            publishNotificationEventFromPostReply(post);
        }

        return new CommentIdElement(comment.getId());
    }

    @Transactional(readOnly = true)
    public GetCommentResDto findComments(Long postId, AuthenticatedMember loginMember) {
        if (!postRepository.existsById(postId)) {
            throw new PostException(PostErrorInfo.NOT_FOUND_POST);
        }

        List<Comment> comments = commentRepository.findAllPostIdWithDetailsFetchOrderByCommentGroupId(postId);
        return GetCommentResDto.of(comments, loginMember);
    }

    @Transactional
    public CommentIdElement updateComment(Long commentId, Long loginMemberId, PatchCommentUpdateReqDto patchCommentUpdateReqDto) {
        Comment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new CommentException(CommentErrorInfo.NOT_FOUND_COMMENT));

        Member loginMember = memberRepository.findById(loginMemberId)
                .orElseThrow(() -> new MemberException(MemberErrorInfo.MEMBER_NOT_FOUND_BY_ID));

        if (!comment.getMember().getId().equals(loginMember.getId())) {
            throw new CommentException(CommentErrorInfo.UNAUTHORIZED_UPDATE_COMMENT);
        }

        comment.updateComment(patchCommentUpdateReqDto.getContent(), patchCommentUpdateReqDto.getAnonymity());
        return new CommentIdElement(comment.getId());
    }

    @Transactional
    public CommentIdElement writeCommentReply(Long postId, Long commentId, Long loginMemberId, PostCommentWriteReqDto postCommentWriteReplyReqDto) {
        Post post = postRepository.findByIdWithMember(postId)
                .orElseThrow(() -> new PostException(PostErrorInfo.NOT_FOUND_POST));

        Member loginMember = memberRepository.findById(loginMemberId)
                .orElseThrow(() -> new MemberException(MemberErrorInfo.MEMBER_NOT_FOUND_BY_ID));

        Comment parentComment = commentRepository.findByIdWithMemberAndCommentGroup(commentId)
                .orElseThrow(() -> new CommentException(CommentErrorInfo.NOT_FOUND_COMMENT));

        if (!parentComment.isAssociatedWithPost(post)) {
            throw new CommentException(CommentErrorInfo.NOT_ASSOCIATED_WITH_POST);
        }

        if (!parentComment.isParent()) {
            throw new CommentException(CommentErrorInfo.FORBIDDEN_REPLY_SUB_COMMENT);
        }

        CommentNumber commentNumber = commentNumberRepository.
                findByPostIdAndMemberId(postId, loginMemberId).orElse(null);

        if (commentNumber == null) {
            commentNumber = CommentNumber.builder()
                    .post(post)
                    .member(loginMember)
                    .number(commentNumberRepository.countAllByPostId(postId) + 1)
                    .build();
            commentNumberRepository.save(commentNumber);
        }

        Comment comment = Comment.builder()
                .post(post)
                .member(loginMember)
                .content(postCommentWriteReplyReqDto.getContent())
                .anonymity(postCommentWriteReplyReqDto.getAnonymity())
                .commentNumber(commentNumber)
                .commentGroup(parentComment)
                .build();

        comment = commentRepository.save(comment);

        Set<Long> visited = new HashSet<>();
        if (!parentComment.isMine(loginMember)) {
            visited.add(parentComment.getAuthorId());
            publishNotificationEventFromCommentReply(post, parentComment);
        }
        if (!post.isMine(loginMember) && !visited.contains(post.getAuthorId())) {
            visited.add(post.getAuthorId());
            publishNotificationEventFromPostReply(post);
        }

        return new CommentIdElement(comment.getId());
    }

    @Transactional
    public PostCommonLikeResDto likeComment(Long commentId, Long loginMemberId) {
        Comment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new CommentException(CommentErrorInfo.NOT_FOUND_COMMENT));

        Member loginMember = memberRepository.findById(loginMemberId)
                .orElseThrow(() -> new MemberException(MemberErrorInfo.MEMBER_NOT_FOUND_BY_ID));

        CommentLike commentLike = commentLikeRepository.findByCommentIdAndMemberId(commentId, loginMember.getId())
                .orElse(null);

        Integer likeCount = commentLikeRepository.countByCommentId(commentId);

        return toggleCommentLike(likeCount, comment, loginMember, commentLike);
    }

    private PostCommonLikeResDto toggleCommentLike(Integer likeCount, Comment comment, Member loginMember, CommentLike commentLike) {
        if (commentLike != null) {
            deleteCommentLike(commentLike);
            return new PostCommonLikeResDto(likeCount - 1, false);
        }
        saveCommentLike(comment, loginMember);
        return new PostCommonLikeResDto(likeCount + 1, true);
    }

    private void saveCommentLike(Comment comment, Member loginMember) {
        CommentLike commentLike = CommentLike.builder()
                .member(loginMember)
                .comment(comment)
                .build();
        commentLikeRepository.save(commentLike);
    }

    private void deleteCommentLike(CommentLike commentLike) {
        commentLikeRepository.delete(commentLike);
    }

    @Transactional
    public CommentIdElement deleteComment(Long commentId, Long loginMemberId) {
        Comment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new CommentException(CommentErrorInfo.NOT_FOUND_COMMENT));

        Member member = memberRepository.findById(loginMemberId)
                .orElseThrow(() -> new MemberException(MemberErrorInfo.MEMBER_NOT_FOUND_BY_ID));

        if (!comment.getMember().getId().equals(member.getId())) {
            throw new CommentException(CommentErrorInfo.UNAUTHORIZED_DELETE_COMMENT);
        }

        commentRepository.delete(comment);
        return new CommentIdElement(comment.getId());
    }

    private void publishNotificationEventFromPostReply(Post post) {
        NotificationEvent notificationEvent = NotificationEvent.builder()
                .ownerId(post.getAuthorId())
                .message(String.format(NotificationMessage.POST_REPLY_MESSAGE.getMessage(), post.getTitle()))
                .contentId(post.getId())
                .serviceType(ServiceType.POST)
                .notificationType(NotificationType.POST_REPLAY)
                .build();
        applicationEventPublisher.publishEvent(notificationEvent);
    }

    private void publishNotificationEventFromCommentReply(Post post, Comment comment) {
        NotificationEvent notificationEvent = NotificationEvent.builder()
                .ownerId(comment.getAuthorId())
                .message(String.format(NotificationMessage.COMMENT_REPLY_MESSAGE.getMessage(), post.getTitle()))
                .contentId(post.getId())
                .serviceType(ServiceType.POST)
                .notificationType(NotificationType.COMMENT_REPLAY)
                .build();
        applicationEventPublisher.publishEvent(notificationEvent);
    }
}
