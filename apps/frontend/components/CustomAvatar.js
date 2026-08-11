import React, { useState, useEffect, useCallback, forwardRef } from 'react';
import { Avatar } from '@vapor-ui/core';
import { generateColorFromEmail, getContrastTextColor } from '@/utils/colorUtils';
import { loadStoredUser } from '@/lib/auth/authStorage';
import profileImageService from '@/services/profileImageService';

/**
 * CustomAvatar 컴포넌트
 * 
 * @param {Object} props
 * @param {Object} props.user - 사용자 객체 (name, email, profileImage 필드)
 * @param {string} props.size - 아바타 크기 ('sm' | 'md' | 'lg' | 'xl')
 * @param {Function} props.onClick - 클릭 핸들러 (있으면 button으로 렌더링)
 * @param {string} props.src - 프로필 이미지 URL (user.profileImage 대신 직접 지정 가능)
 * @param {boolean} props.showImage - 이미지 표시 여부 (기본값: true)
 * @param {boolean} props.persistent - 실시간 프로필 업데이트 감지 여부 (기본값: false)
 * @param {boolean} props.showInitials - 이니셜 표시 여부 (기본값: true)
 * @param {string} props.className - 추가 CSS 클래스
 * @param {Object} props.style - 추가 인라인 스타일
 */
const CustomAvatar = forwardRef(({
  user,
  size = 'md',
  onClick,
  src,
  showImage = true,
  persistent = false,
  showInitials = true,
  className = '',
  style = {},
  ...props
}, ref) => {
  // persistent 모드일 때만 상태 관리
  const [currentImage, setCurrentImage] = useState('');
  const [imageError, setImageError] = useState(false);

  // 이메일 기반 배경색/텍스트 색상 생성
  const backgroundColor = generateColorFromEmail(user?.email);
  const color = getContrastTextColor(backgroundColor);

  // 프로필 이미지는 공개 경로를 직접 조립하지 않고 인증된 access URL을 발급받는다.
  useEffect(() => {
    let active = true;
    profileImageService.getUrl({
      id: user?.id,
      _id: user?._id,
      profileImage: user?.profileImage,
    }, src)
      .then((imageUrl) => {
        if (!active) return;
        setImageError(false);
        setCurrentImage(imageUrl || '');
      })
      .catch(() => active && setImageError(true));
    return () => { active = false; };
  }, [user?.id, user?._id, user?.profileImage, src]);

  // persistent 모드: 전역 프로필 업데이트 리스너
  useEffect(() => {
    if (!persistent) return;

    const handleProfileUpdate = () => {
      try {
        const updatedUser = loadStoredUser() || {};
        // 현재 사용자의 프로필이 업데이트된 경우에만 이미지 업데이트
        if (user?.id === updatedUser.id && updatedUser.profileImage !== user.profileImage) {
          profileImageService.invalidate(updatedUser.id || updatedUser._id);
          profileImageService.getUrl(updatedUser)
            .then((newImageUrl) => {
              setImageError(false);
              setCurrentImage(newImageUrl || '');
            })
            .catch(() => setImageError(true));
        }
      } catch (error) {
        console.error('Profile update handling error:', error);
      }
    };
    
    window.addEventListener('userProfileUpdate', handleProfileUpdate);
    return () => {
      window.removeEventListener('userProfileUpdate', handleProfileUpdate);
    };
  }, [persistent, user?.id, user?.profileImage]);

  // 이미지 에러 핸들러
  const handleImageError = useCallback((e) => {
    e.preventDefault();
    setImageError(true);

    console.debug('Avatar image load failed:', {
      user: user?.name,
      email: user?.email,
      imageUrl: currentImage
    });
  }, [currentImage, user?.name, user?.email]);

  // 최종 이미지 URL 결정
  const finalImageUrl = (() => {
    if (!showImage) return undefined;
    
    return currentImage && !imageError ? currentImage : undefined;
  })();

  // 사용자 이름 첫 글자
  const initial = showInitials ? (user?.name?.charAt(0)?.toUpperCase() || '?') : '';
  const imageAlt = user?.name ? `${user.name} 프로필 이미지` : '프로필 이미지';

  // 클릭 가능한 경우 button으로 렌더링
  const renderProp = onClick ? <button onClick={onClick} /> : undefined;

  return (
    <Avatar.Root
      ref={ref}
      key={user?._id || user?.id}
      shape="circle"
      size={size}
      render={renderProp}
      src={finalImageUrl}
      className={className}
      style={{
        backgroundColor,
        color,
        cursor: onClick ? 'pointer' : 'default',
        ...style
      }}
      {...props}
    >
      {finalImageUrl && (
        <Avatar.ImagePrimitive 
          onError={handleImageError}
          alt={imageAlt}
        />
      )}
      <Avatar.FallbackPrimitive style={{ backgroundColor, color, fontWeight: '500' }}>
        {initial}
      </Avatar.FallbackPrimitive>
    </Avatar.Root>
  );
});

CustomAvatar.displayName = 'CustomAvatar';

export default CustomAvatar;
