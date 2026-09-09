    location = /upgrid-diagnostics/v1/reports {
        client_max_body_size 96k;
        client_body_timeout 10s;
        limit_conn perip 4;
        limit_except POST { deny all; }
        proxy_pass http://127.0.0.1:28610;
        proxy_connect_timeout 3s;
        proxy_read_timeout 20s;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $remote_addr;
        access_log off;
    }
