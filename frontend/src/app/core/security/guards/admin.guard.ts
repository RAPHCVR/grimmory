import {inject} from '@angular/core';
import {CanActivateFn, Router} from '@angular/router';
import {UserService} from '../../../features/settings/user-management/user.service';
import {filter, map, take} from 'rxjs/operators';

export const AdminGuard: CanActivateFn = () => {
  const userService = inject(UserService);
  const router = inject(Router);

  return userService.userState$.pipe(
    filter(state => state.loaded),
    take(1),
    map(state => {
      if (state.user?.permissions.admin) {
        return true;
      }
      return router.createUrlTree(['/dashboard']);
    })
  );
};
