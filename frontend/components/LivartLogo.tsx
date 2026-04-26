import React from 'react';

interface LivartLogoProps {
  size?: number;
  className?: string;
  title?: string;
}

const LivartLogo: React.FC<LivartLogoProps> = ({
  size = 40,
  className = '',
  title = 'livart'
}) => (
  <svg
    width={size}
    height={size}
    viewBox="0 0 64 64"
    role="img"
    aria-label={title}
    className={className}
    fill="none"
    xmlns="http://www.w3.org/2000/svg"
  >
    <rect width="64" height="64" rx="18" fill="#050505" />
    <path
      d="M21.5 44.5V20.2C21.5 14.9 25 11.5 30.1 11.5C35 11.5 38.4 14.8 38.4 19.1C38.4 25 33.4 28.5 25.7 28.5H21.5"
      stroke="white"
      strokeWidth="5.2"
      strokeLinecap="round"
      strokeLinejoin="round"
    />
    <path
      d="M31.3 43.5C36 47.1 44.9 46.2 48.6 39.3C52 32.8 48.2 24.7 40.8 23.9C33.1 23 28.3 29.4 29.4 35.1C30.3 40 35.6 42.2 40.2 39.9C43.4 38.3 44.8 35.1 43.7 31.9"
      stroke="white"
      strokeWidth="5.2"
      strokeLinecap="round"
      strokeLinejoin="round"
    />
    <circle cx="44.8" cy="15.8" r="3.1" fill="white" />
  </svg>
);

export default LivartLogo;
