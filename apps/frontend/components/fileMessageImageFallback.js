export const handleImagePreviewError = (event) => {
  const image = event.currentTarget;
  image.onerror = null;
  image.src = '/images/placeholder-image.png';
};
