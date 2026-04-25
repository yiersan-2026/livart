export interface AuthUser {
  id: string;
  username: string;
  displayName: string;
  createdAt?: string;
}

export interface AuthSession {
  user: AuthUser;
  token: string;
  expiresAt: string;
}

interface ApiResponse<T> {
  success: boolean;
  data?: T;
  error?: {
    message: string;
    code: string;
  };
}

const AUTH_SESSION_KEY = 'livart_auth_session';

const unwrapApiResponse = async <T>(response: Response): Promise<T> => {
  const payload = await response.json().catch(() => null) as ApiResponse<T> | null;
  if (!response.ok || !payload?.success) {
    throw new Error(payload?.error?.message || `认证请求失败：${response.status}`);
  }
  if (payload.data === undefined) {
    throw new Error('认证响应为空');
  }
  return payload.data;
};

export const getStoredAuthSession = (): AuthSession | null => {
  const rawSession = localStorage.getItem(AUTH_SESSION_KEY);
  if (!rawSession) return null;

  try {
    const session = JSON.parse(rawSession) as AuthSession;
    if (!session?.token || !session?.user?.id || !session.expiresAt) return null;
    if (new Date(session.expiresAt).getTime() <= Date.now()) return null;
    return session;
  } catch {
    return null;
  }
};

export const saveAuthSession = (session: AuthSession) => {
  localStorage.setItem(AUTH_SESSION_KEY, JSON.stringify(session));
};

export const clearAuthSession = () => {
  localStorage.removeItem(AUTH_SESSION_KEY);
};

export const authHeaders = () => {
  const session = getStoredAuthSession();
  return session ? { Authorization: `Bearer ${session.token}` } : {};
};

export const register = async (username: string, password: string, displayName?: string) => {
  const response = await fetch('/api/auth/register', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json'
    },
    body: JSON.stringify({ username, password, displayName })
  });
  const session = await unwrapApiResponse<AuthSession>(response);
  saveAuthSession(session);
  return session;
};

export const login = async (username: string, password: string) => {
  const response = await fetch('/api/auth/login', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json'
    },
    body: JSON.stringify({ username, password })
  });
  const session = await unwrapApiResponse<AuthSession>(response);
  saveAuthSession(session);
  return session;
};

export const loadCurrentUser = async () => {
  const storedSession = getStoredAuthSession();
  if (!storedSession) return null;

  const response = await fetch('/api/auth/me', {
    headers: {
      Accept: 'application/json',
      ...authHeaders()
    }
  });
  const user = await unwrapApiResponse<AuthUser>(response);
  const session = { ...storedSession, user };
  saveAuthSession(session);
  return session;
};

export const logout = async () => {
  const headers = authHeaders();
  clearAuthSession();
  await fetch('/api/auth/logout', {
    method: 'POST',
    headers: {
      Accept: 'application/json',
      ...headers
    }
  }).catch(() => undefined);
};
