FROM httpd:2.4
RUN /usr/local/apache2/bin/htpasswd -bc /usr/local/apache2/conf/dav.htpasswd alice 'pa:ss/1' \
 && /usr/local/apache2/bin/htpasswd -b /usr/local/apache2/conf/dav.htpasswd bob bobpass
COPY dav.conf /usr/local/apache2/conf/extra/dav.conf
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh \
 && echo "Include conf/extra/dav.conf" >> /usr/local/apache2/conf/httpd.conf \
 && sed -i -e 's/^#\(LoadModule dav_module\)/\1/' -e 's/^#\(LoadModule dav_fs_module\)/\1/' -e 's/^#\(LoadModule dav_lock_module\)/\1/' /usr/local/apache2/conf/httpd.conf
ENTRYPOINT ["/entrypoint.sh"]
