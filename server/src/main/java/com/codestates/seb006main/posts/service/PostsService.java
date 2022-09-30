package com.codestates.seb006main.posts.service;

import com.amazonaws.services.s3.AmazonS3Client;
import com.codestates.seb006main.Image.entity.Image;
import com.codestates.seb006main.Image.repository.ImageRepository;
import com.codestates.seb006main.auth.PrincipalDetails;
import com.codestates.seb006main.dto.MultiResponseDto;
import com.codestates.seb006main.exception.BusinessLogicException;
import com.codestates.seb006main.exception.ExceptionCode;
import com.codestates.seb006main.matching.entity.Matching;
import com.codestates.seb006main.matching.mapper.MatchingMapper;
import com.codestates.seb006main.posts.dto.PostsCond;
import com.codestates.seb006main.posts.dto.PostsDto;
import com.codestates.seb006main.posts.entity.MemberPosts;
import com.codestates.seb006main.posts.entity.Posts;
import com.codestates.seb006main.posts.mapper.MemberPostsMapper;
import com.codestates.seb006main.posts.mapper.PostsMapper;
import com.codestates.seb006main.posts.repository.MemberPostsRepository;
import com.codestates.seb006main.posts.repository.PostsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
@Service
public class PostsService {
    private final PostsRepository postsRepository;
    private final ImageRepository imageRepository;
    private final PostsMapper postsMapper;
    final AmazonS3Client amazonS3Client;
    private final String S3Bucket = "seb-main-006/img";
    private final MemberPostsRepository memberPostsRepository;
    private final MemberPostsMapper memberPostsMapper;
    private final MatchingMapper matchingMapper;

    public PostsDto.Response createPosts(PostsDto.Post postDto, Authentication authentication) {
        Posts posts = postsMapper.postDtoToPosts(postDto);
        PrincipalDetails principalDetails = (PrincipalDetails) authentication.getPrincipal();
        posts.setMember(principalDetails.getMember());
        postsRepository.save(posts);

        MemberPosts memberPosts = MemberPosts.builder().member(principalDetails.getMember()).build();
        memberPosts.setPosts(posts);
        memberPostsRepository.save(memberPosts);


        if (postDto.getImages() != null) {
            saveImages(postDto.getImages(), posts);
        }

        return postsMapper.postsToResponseDto(posts);
    }

    public PostsDto.Response readPosts(Long postId) {
        Posts posts = postsRepository.findById(postId).orElseThrow(() -> new BusinessLogicException(ExceptionCode.POST_NOT_FOUND));
        return postsMapper.postsToResponseDto(posts);
    }

    public MultiResponseDto readAllPosts(PageRequest pageRequest, PostsCond postsCond) {
        Page<Posts> postsPage = postsRepository.findAllWithCondition(postsCond, pageRequest);
        List<Posts> postsList = postsPage.getContent();
        return new MultiResponseDto<>(postsMapper.postsListToResponseDtoList(postsList), postsPage);
    }

    public MultiResponseDto readAllMatching(Long postId, PageRequest pageRequest) {
        Posts posts = postsRepository.findById(postId).orElseThrow(() -> new BusinessLogicException(ExceptionCode.POST_NOT_FOUND));
        List<Matching> matchingList = posts.getMatching();
        Page<Matching> matchingPage = new PageImpl<>(matchingList, pageRequest, matchingList.size());
        return new MultiResponseDto<>(matchingMapper.matchingListToResponseDtoList(matchingList), matchingPage);
    }

    public MultiResponseDto readAllParticipants(Long postId, PageRequest pageRequest) {
        Posts posts = postsRepository.findById(postId).orElseThrow(() -> new BusinessLogicException(ExceptionCode.POST_NOT_FOUND));
        List<MemberPosts> participantsList = posts.getParticipants();
        Page<MemberPosts> participantsPage = new PageImpl<>(participantsList, pageRequest, participantsList.size());
        return new MultiResponseDto<>(memberPostsMapper.memberPostsListToMemberParticipantsList(participantsList), participantsPage);
    }

    public PostsDto.Response updatePosts(Long postId, PostsDto.Patch patchDto, Authentication authentication) {
        PrincipalDetails principalDetails = (PrincipalDetails) authentication.getPrincipal();
        Posts posts = postsRepository.findById(postId).orElseThrow(() -> new BusinessLogicException(ExceptionCode.POST_NOT_FOUND));

        if (posts.getMember().getMemberId() != principalDetails.getMember().getMemberId()) {
            throw new BusinessLogicException(ExceptionCode.PERMISSION_DENIED);
        }

        // TODO: 필요한가?
        Optional.ofNullable(patchDto.getTitle())
                .ifPresent(posts::updateTitle);
        Optional.ofNullable(patchDto.getBody())
                .ifPresent(posts::updateBody);
        Optional.ofNullable(patchDto.getCloseDate())
                .ifPresent(posts::updateCloseDate);
        Optional.ofNullable(patchDto.getTotalCount())
                .ifPresent(posts::updateTotalCount);

        //TODO: 이미지 수정 로직 -> 프론트와 지속적으로 주고받는 데이터에 대한 상의가 필요함.

        posts.updatePosts(patchDto.getTitle(), patchDto.getBody(), patchDto.getTotalCount(), patchDto.getCloseDate());
        postsRepository.save(posts);
        return postsMapper.postsToResponseDto(posts);
    }

    public void deletePosts(Long postId, Authentication authentication) {
        PrincipalDetails principalDetails = (PrincipalDetails) authentication.getPrincipal();
        Posts posts = postsRepository.findById(postId)
                .orElseThrow(() -> new BusinessLogicException(ExceptionCode.POST_NOT_FOUND));
        if (posts.getMember().getMemberId() != principalDetails.getMember().getMemberId()) {
            throw new BusinessLogicException(ExceptionCode.PERMISSION_DENIED);
        }
        if (posts.getImages().size() != 0) {
            for (Image image : posts.getImages()) {
                String imagePath = image.getStoredPath();
                amazonS3Client.deleteObject(S3Bucket, imagePath.substring(imagePath.lastIndexOf("/") + 1));
                imageRepository.deleteAll(posts.getImages());
                posts.getImages().clear();
            }
        }
        posts.inactive();
        postsRepository.save(posts);
    }

    public void deleteParticipant(Long participantId, Authentication authentication) {
        PrincipalDetails principalDetails = (PrincipalDetails) authentication.getPrincipal();
        MemberPosts memberPosts = memberPostsRepository.findById(participantId).orElseThrow(() -> new BusinessLogicException(ExceptionCode.PARTICIPANT_NOT_FOUND));
        Posts posts = postsRepository.findById(memberPosts.getPosts().getPostId())
                .orElseThrow(() -> new BusinessLogicException(ExceptionCode.POST_NOT_FOUND));
        if (posts.getMember().getMemberId() != principalDetails.getMember().getMemberId() &&
                memberPosts.getMember().getMemberId() != principalDetails.getMember().getMemberId()) {
            throw new BusinessLogicException(ExceptionCode.PERMISSION_DENIED);
        }
        memberPostsRepository.deleteById(participantId);
    }

    public void saveImages(List<Long> images, Posts posts) {
        for (Long imageId : images) {
            Image image = imageRepository.findById(imageId).orElseThrow(() -> new BusinessLogicException(ExceptionCode.IMAGE_NOT_FOUND));
            image.setPosts(posts);
            imageRepository.save(image);
        }
    }
}
