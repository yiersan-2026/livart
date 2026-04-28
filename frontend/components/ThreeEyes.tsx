import React, { useEffect, useRef } from 'react';

interface ThreeEyesProps {
  width?: number;
  height?: number;
  className?: string;
}

const createEye = (THREE: typeof import('three')) => {
  const eyeGroup = new THREE.Group();
  const dotGeometry = new THREE.CircleGeometry(1, 18);
  const dotMaterial = new THREE.MeshBasicMaterial({
    color: '#18181b',
    transparent: true,
    opacity: 0.96,
    side: THREE.DoubleSide
  });
  const dots: Array<{ x: number; y: number; radius: number }> = [];

  for (let yIndex = -8; yIndex <= 8; yIndex += 1) {
    for (let xIndex = -18; xIndex <= 18; xIndex += 1) {
      const x = xIndex * 0.095;
      const y = yIndex * 0.082;
      const normalizedX = Math.abs(x) / 1.72;
      const eyeLimit = normalizedX >= 1 ? 0 : 0.62 * Math.pow(1 - Math.pow(normalizedX, 1.7), 0.56);
      const isInsideEye = Math.abs(y) <= eyeLimit;
      if (!isInsideEye) continue;

      const irisDistance = Math.hypot(x / 0.55, y / 0.48);
      const pupilDistance = Math.hypot(x / 0.24, y / 0.22);

      const centerFalloff = Math.max(0, 1 - Math.hypot(x / 1.55, y / 0.7));
      const irisStrength = Math.max(0, 1 - irisDistance);
      const pupilStrength = Math.max(0, 1 - pupilDistance);
      const lidStrength = Math.max(0, 1 - Math.abs(Math.abs(y) - eyeLimit) / 0.18);
      const radius = 0.01
        + centerFalloff * 0.026
        + irisStrength * 0.06
        + pupilStrength * 0.052
        + lidStrength * 0.018;

      dots.push({ x, y, radius });
    }
  }

  const dotMesh = new THREE.InstancedMesh(dotGeometry, dotMaterial, dots.length);
  const dotMatrix = new THREE.Matrix4();
  dots.forEach((dot, index) => {
    dotMatrix.compose(
      new THREE.Vector3(dot.x, dot.y, 0),
      new THREE.Quaternion(),
      new THREE.Vector3(dot.radius, dot.radius, 1)
    );
    dotMesh.setMatrixAt(index, dotMatrix);
  });
  dotMesh.instanceMatrix.needsUpdate = true;
  eyeGroup.add(dotMesh);

  return eyeGroup;
};

const ThreeEyes: React.FC<ThreeEyesProps> = ({ width = 64, height = 42, className }) => {
  const mountRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    const mount = mountRef.current;
    if (!mount) return undefined;

    let cancelled = false;
    let disposeScene: (() => void) | undefined;

    void import('three').then((THREE) => {
      if (cancelled) return;

      const renderer = new THREE.WebGLRenderer({ alpha: true, antialias: true });
      renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
      renderer.setSize(width, height, false);
      renderer.outputColorSpace = THREE.SRGBColorSpace;
      renderer.domElement.style.width = '100%';
      renderer.domElement.style.height = '100%';
      renderer.domElement.style.display = 'block';
      mount.appendChild(renderer.domElement);

      const scene = new THREE.Scene();
      const camera = new THREE.OrthographicCamera(-1.9, 1.9, 1.22, -1.22, 0.1, 100);
      camera.position.set(0, 0, 5);
      camera.lookAt(0, 0, 0);

      const group = new THREE.Group();
      scene.add(group);

      group.add(createEye(THREE));

      renderer.render(scene, camera);

      disposeScene = () => {
        if (renderer.domElement.parentElement === mount) {
          mount.removeChild(renderer.domElement);
        }
        renderer.dispose();
        scene.traverse(object => {
          if (object instanceof THREE.Mesh) {
            object.geometry.dispose();
            const materials = Array.isArray(object.material) ? object.material : [object.material];
            materials.forEach(material => material.dispose());
          }
        });
      };
    });

    return () => {
      cancelled = true;
      disposeScene?.();
    };
  }, [height, width]);

  return (
    <div
      ref={mountRef}
      aria-hidden="true"
      className={className}
      style={{ width, height }}
    />
  );
};

export default ThreeEyes;
