import React, { useEffect, useRef } from 'react';
import type { Viewer as PhotoSphereViewer } from '@photo-sphere-viewer/core';

interface PanoramaViewerProps {
  src: string;
  title?: string;
  compact?: boolean;
}

const PanoramaViewer: React.FC<PanoramaViewerProps> = ({ src, title, compact = false }) => {
  const containerRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    if (!containerRef.current || !src) return;

    let disposed = false;
    let viewer: PhotoSphereViewer | null = null;

    Promise.all([
      import('@photo-sphere-viewer/core'),
      import('@photo-sphere-viewer/core/index.css')
    ]).then(([module]) => {
      if (disposed || !containerRef.current) return;

      viewer = new module.Viewer({
        container: containerRef.current,
        panorama: src,
        caption: title || '360° 全景图',
        defaultZoomLvl: compact ? 20 : 35,
        minFov: 30,
        maxFov: 100,
        mousewheel: !compact,
        mousemove: true,
        keyboard: compact ? false : 'always',
        navbar: compact ? ['zoom', 'move', 'fullscreen'] : ['zoom', 'move', 'caption', 'fullscreen']
      });
    });

    return () => {
      disposed = true;
      viewer?.destroy();
    };
  }, [compact, src, title]);

  return <div ref={containerRef} className="h-full w-full overflow-hidden bg-black" />;
};

export default PanoramaViewer;
