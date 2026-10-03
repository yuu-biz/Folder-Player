FROM python:3.12-alpine
RUN pip install --no-cache-dir pyftpdlib==2.0.1 pyopenssl==24.2.1
COPY ftpserver.py /ftpserver.py
ENTRYPOINT ["python", "-u", "/ftpserver.py"]
