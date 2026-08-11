import { redirect } from 'next/navigation';

const LoginRedirectPage = async ({ searchParams }) => {
  const params = await searchParams;
  const query = new URLSearchParams();

  Object.entries(params || {}).forEach(([key, value]) => {
    const values = Array.isArray(value) ? value : [value];
    values.forEach((item) => {
      if (item !== undefined) query.append(key, item);
    });
  });

  const queryString = query.toString();
  redirect(queryString ? `/?${queryString}` : '/');
};

export default LoginRedirectPage;
